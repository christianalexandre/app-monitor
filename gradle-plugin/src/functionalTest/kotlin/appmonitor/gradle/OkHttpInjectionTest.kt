package appmonitor.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Builds consumer apps with AGP that apply only the plugin, the SDK stood in by projects at this repository's paths
 * (`appmonitor.sdk.local`), and reads their APKs with `dexdump` (ADR-0112, ADR-0157).
 */
class OkHttpInjectionTest {
    private val sdk = File(checkNotNull(System.getProperty("appmonitor.androidSdk")).ifEmpty { error("set ANDROID_HOME") })
    private val okhttp = System.getProperty("appmonitor.okhttp")
    private val compileSdk = System.getProperty("appmonitor.compileSdk")
    private val dir = File("build/consumer").absoluteFile.apply { deleteRecursively() }

    private fun write(
        path: String,
        text: String,
    ) = dir.resolve(path).apply { parentFile.mkdirs() }.writeText(text.trimIndent())

    private fun app(
        name: String,
        extra: String,
        withOkHttp: Boolean = true,
    ) {
        write(
            "$name/build.gradle.kts",
            """
            plugins {
                id("com.android.application")
                id("io.github.christianalexandre.appmonitor")
            }
            android {
                namespace = "consumer.$name"
                compileSdk = $compileSdk
                defaultConfig { minSdk = 24 }
                lint { checkReleaseBuilds = false }
            }
            ${if (withOkHttp) """dependencies { implementation("com.squareup.okhttp3:okhttp:$okhttp") }""" else ""}
            $extra
            """,
        )
        write("$name/src/main/AndroidManifest.xml", "<manifest />")
    }

    /** A stand-in SDK module at [path]: one class, [name], in [pkg], and [deps] in its `dependencies {}`. */
    private fun stub(
        path: String,
        pkg: String,
        name: String,
        body: String = "",
        deps: String = "",
    ) {
        val dirPath = path.removePrefix(":").replace(':', '/')
        write(
            "$dirPath/build.gradle.kts",
            """
            plugins { `java-library` }
            dependencies { $deps }
            tasks.withType<JavaCompile>().configureEach { options.release = 11 }
            """,
        )
        write("$dirPath/src/main/java/${pkg.replace('.', '/')}/$name.java", "package $pkg;\npublic final class $name { $body }")
    }

    private fun dex(apk: String): String {
        val dexdump =
            sdk
                .resolve("build-tools")
                .listFiles()
                .orEmpty()
                .maxBy { it.name }
                .resolve("dexdump")
        val process = ProcessBuilder(dexdump.path, "-d", dir.resolve(apk).path).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { out }
        return out
    }

    /** The calls to the entry point in an APK's code. */
    private fun installCalls(apk: String): Int =
        Regex("""invoke-static \{v\d+}, Lappmonitor/sdk/okhttp/AppMonitorOkHttp;\.install:""").findAll(dex(apk)).count()

    private fun hasClass(
        apk: String,
        descriptor: String,
    ) = "Class descriptor  : '$descriptor'" in dex(apk)

    private fun consumer(vararg apps: String) {
        write("local.properties", "sdk.dir=${sdk.path}")
        write("gradle.properties", "android.builtInKotlin=false\norg.gradle.jvmargs=-Xmx2g\nappmonitor.sdk.local=true")
        write(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { google(); mavenCentral() } }
            dependencyResolutionManagement {
                repositories { google(); mavenCentral(); maven(url = uri("repo")) }
            }
            include(":sdk:core", ":sdk:core-no-op", ":sdk:android-okhttp", ":sdk:okhttp-no-op", ":sdk:ktor", ":sdk:ktor-no-op")
            include(${apps.joinToString { "\":$it\"" }})
            """,
        )
        stub(":sdk:core", "appmonitor.sdk.core", "RealCore")
        stub(":sdk:core-no-op", "appmonitor.sdk.core", "NoOpCore")
        val install = "public static void install(okhttp3.OkHttpClient.Builder builder) {}"
        val okhttpApi = """api("com.squareup.okhttp3:okhttp:$okhttp")"""
        stub(":sdk:android-okhttp", "appmonitor.sdk.okhttp", "AppMonitorOkHttp", install, okhttpApi)
        stub(":sdk:okhttp-no-op", "appmonitor.sdk.okhttp", "AppMonitorOkHttp", install, okhttpApi)
        stub(":sdk:ktor", "appmonitor.sdk.ktor", "RealKtor")
        stub(":sdk:ktor-no-op", "appmonitor.sdk.ktor", "NoOpKtor")
    }

    private fun gradle(vararg tasks: String) =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(*tasks, "--stacktrace")
            .forwardOutput()

    @Test
    fun `adds the SDK to debug, the no-ops to release, and rewrites OkHttp in debug only`() {
        consumer("app", "optout", "bare")
        app("app", "")
        app("optout", "appMonitor { injectOkHttp = false }")
        app("bare", "", withOkHttp = false)

        gradle(":app:assembleDebug", ":app:assembleRelease", ":optout:assembleDebug", ":bare:assembleDebug").build()

        val debug = "app/build/outputs/apk/debug/app-debug.apk"
        val release = "app/build/outputs/apk/release/app-release-unsigned.apk"
        assertEquals(1, installCalls(debug))
        assertTrue(hasClass(debug, "Lappmonitor/sdk/core/RealCore;"))
        assertFalse(hasClass(debug, "Lappmonitor/sdk/core/NoOpCore;"))
        assertEquals(0, installCalls(release))
        assertTrue(hasClass(release, "Lappmonitor/sdk/core/NoOpCore;"))
        assertFalse(hasClass(release, "Lappmonitor/sdk/core/RealCore;"))
        assertEquals(0, installCalls("optout/build/outputs/apk/debug/optout-debug.apk"))
        val bare = "bare/build/outputs/apk/debug/bare-debug.apk"
        assertTrue(hasClass(bare, "Lappmonitor/sdk/core/RealCore;"))
        assertFalse(hasClass(bare, "Lokhttp3/OkHttpClient;"), "the plugin must not bring OkHttp to an app without it")
    }

    @Test
    fun `fails a release build that resolves a real SDK artifact`() {
        consumer("leaky")
        // A per-target artifact the swap does not name, declared by hand.
        val module = dir.resolve("repo/io/github/christianalexandre/appmonitor-core-android/1.0").apply { mkdirs() }
        module.resolve("appmonitor-core-android-1.0.pom").writeText(
            """
            <project><modelVersion>4.0.0</modelVersion><groupId>io.github.christianalexandre</groupId>
            <artifactId>appmonitor-core-android</artifactId><version>1.0</version></project>
            """.trimIndent(),
        )
        ZipOutputStream(module.resolve("appmonitor-core-android-1.0.jar").outputStream()).use { it.putNextEntry(ZipEntry("META-INF/")) }
        app("leaky", """dependencies { releaseImplementation("io.github.christianalexandre:appmonitor-core-android:1.0") }""")

        val result = gradle(":leaky:assembleRelease").buildAndFail()

        assertTrue("resolves the real SDK" in result.output, result.output)
        assertTrue("appmonitor-core-android" in result.output, result.output)
    }
}
