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
    private val sdkVersion = System.getProperty("appmonitor.sdkVersion")
    private val dir = File("build/consumer").absoluteFile.apply { deleteRecursively() }
    private val dexed = mutableMapOf<String, String>()

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

    /**
     * A stand-in SDK module at [path]: one class, [name], in [pkg], and [deps] in its `dependencies {}`. With
     * [artifact], it lives under `sdk-build/` and is published to `repo/` with the plugin's coordinates.
     */
    private fun stub(
        path: String,
        pkg: String,
        name: String,
        body: String = "",
        deps: String = "",
        artifact: String? = null,
    ) {
        val dirPath = (if (artifact != null) "sdk-build/" else "") + path.removePrefix(":").replace(':', '/')
        val publish =
            if (artifact == null) {
                ""
            } else {
                """
                group = "io.github.christianalexandre"
                version = "$sdkVersion"
                publishing {
                    publications { create<MavenPublication>("sdk") { artifactId = "$artifact"; from(components["java"]) } }
                    repositories { maven(url = uri("${dir.resolve("repo").path}")) }
                }
                """
            }
        write(
            "$dirPath/build.gradle.kts",
            """
            plugins { `java-library`; `maven-publish` }
            repositories { mavenCentral() }
            dependencies { $deps }
            tasks.withType<JavaCompile>().configureEach { options.release = 11 }
            $publish
            """,
        )
        write("$dirPath/src/main/java/${pkg.replace('.', '/')}/$name.java", "package $pkg;\npublic final class $name { $body }")
    }

    private fun dex(apk: String): String = dexed.getOrPut(apk) { dump(apk) }

    private fun dump(apk: String): String {
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

    /** The calls to an entry point, `install` or `newWebSocket`, in an APK's code. */
    private fun entryCalls(
        apk: String,
        entry: String = "install",
    ): Int = Regex("""invoke-static \{[v\d, ]+}, Lappmonitor/sdk/okhttp/AppMonitorOkHttp;\.$entry:""").findAll(dex(apk)).count()

    private fun hasClass(
        apk: String,
        descriptor: String,
    ) = "Class descriptor  : '$descriptor'" in dex(apk)

    /** [local]: the SDK projects in the consumer's build; else published to `repo/` first, as from Maven. */
    private fun consumer(
        vararg apps: String,
        local: Boolean = true,
    ) {
        write("local.properties", "sdk.dir=${sdk.path}")
        write("gradle.properties", "android.builtInKotlin=false\norg.gradle.jvmargs=-Xmx2g\nappmonitor.sdk.local=$local")
        val projects = SDK.joinToString { "\"${it.first}\"" }
        write(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { google(); mavenCentral() } }
            dependencyResolutionManagement {
                repositories { google(); mavenCentral(); maven(url = uri("repo")) }
            }
            ${if (local) "include($projects)" else ""}
            include(${apps.joinToString { "\":$it\"" }})
            """,
        )
        val install =
            "public static void install(okhttp3.OkHttpClient.Builder builder) {}\n" +
                "public static okhttp3.WebSocket newWebSocket(okhttp3.OkHttpClient c, okhttp3.Request r, okhttp3.WebSocketListener l) { return null; }"
        val okhttpApi = """api("com.squareup.okhttp3:okhttp:$okhttp")"""
        for ((path, artifact) in SDK) {
            val pkg = "appmonitor.sdk." + path.substringAfterLast(':').removePrefix("android-").removeSuffix("-no-op")
            val okhttpModule = "okhttp" in path
            val name =
                when {
                    okhttpModule -> "AppMonitorOkHttp"
                    path.endsWith("-no-op") -> "NoOp" + pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase)
                    else -> "Real" + pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase)
                }
            stub(path, pkg, name, if (okhttpModule) install else "", if (okhttpModule) okhttpApi else "", artifact.takeUnless { local })
        }
        if (!local) {
            write("sdk-build/settings.gradle.kts", "include($projects)")
            GradleRunner
                .create()
                .withProjectDir(dir.resolve("sdk-build"))
                .withArguments("publish")
                .forwardOutput()
                .build()
        }
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
        // R8 on: the no-op names OkHttp, which this app has not; R8 drops it unreported (ADR-0157).
        app("bare", "android { buildTypes { release { isMinifyEnabled = true } } }", withOkHttp = false)

        gradle(
            ":app:assembleDebug",
            ":app:assembleRelease",
            ":optout:assembleDebug",
            ":bare:assembleDebug",
            ":bare:assembleRelease",
        ).build()

        val debug = "app/build/outputs/apk/debug/app-debug.apk"
        val release = "app/build/outputs/apk/release/app-release-unsigned.apk"
        assertEquals(1, entryCalls(debug))
        assertEquals(1, entryCalls(debug, "newWebSocket"))
        assertTrue(hasClass(debug, "Lappmonitor/sdk/core/RealCore;"))
        assertFalse(hasClass(debug, "Lappmonitor/sdk/core/NoOpCore;"))
        assertEquals(0, entryCalls(release))
        assertEquals(0, entryCalls(release, "newWebSocket"))
        assertTrue(hasClass(release, "Lappmonitor/sdk/core/NoOpCore;"))
        assertFalse(hasClass(release, "Lappmonitor/sdk/core/RealCore;"))
        assertEquals(0, entryCalls("optout/build/outputs/apk/debug/optout-debug.apk"))
        val bare = "bare/build/outputs/apk/debug/bare-debug.apk"
        assertTrue(hasClass(bare, "Lappmonitor/sdk/core/RealCore;"))
        assertFalse(hasClass(bare, "Lokhttp3/OkHttpClient;"), "the plugin must not bring OkHttp to an app without it")
    }

    @Test
    fun `adds the published SDK and builds a release with its no-ops`() {
        consumer("app", local = false)
        app("app", "")

        gradle(":app:assembleDebug", ":app:assembleRelease").build()

        assertTrue(hasClass("app/build/outputs/apk/debug/app-debug.apk", "Lappmonitor/sdk/core/RealCore;"))
        val release = "app/build/outputs/apk/release/app-release-unsigned.apk"
        assertTrue(hasClass(release, "Lappmonitor/sdk/core/NoOpCore;"))
        assertFalse(hasClass(release, "Lappmonitor/sdk/core/RealCore;"))
    }

    @Test
    fun `a dynamic feature holds no SDK, its app does`() {
        consumer("base", "feature")
        app("base", """android { dynamicFeatures += ":feature" }""")
        write(
            "feature/build.gradle.kts",
            """
            plugins { id("com.android.dynamic-feature") }
            android {
                namespace = "consumer.feature"
                compileSdk = $compileSdk
                defaultConfig { minSdk = 24 }
            }
            dependencies { implementation(project(":base")) }
            """,
        )
        write(
            "feature/src/main/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:dist="http://schemas.android.com/apk/distribution">
                <dist:module dist:title="@string/app_name"><dist:fusing dist:include="true" /><dist:delivery><dist:on-demand /></dist:delivery></dist:module>
                <application android:hasCode="false" />
            </manifest>
            """,
        )
        write("feature/src/main/res/values/strings.xml", "<resources><string name=\"app_name\">f</string></resources>")
        write("base/src/main/res/values/strings.xml", "<resources><string name=\"app_name\">b</string></resources>")

        gradle(":base:assembleDebug", ":feature:assembleDebug", ":base:assembleRelease", ":feature:assembleRelease").build()

        // The app's APK carries the SDK or its no-ops for the feature; the feature's carries neither.
        assertTrue(hasClass("base/build/outputs/apk/debug/base-debug.apk", "Lappmonitor/sdk/core/RealCore;"))
        assertTrue(hasClass("base/build/outputs/apk/release/base-release-unsigned.apk", "Lappmonitor/sdk/core/NoOpCore;"))
        for (apk in listOf("feature/build/outputs/apk/debug/feature-debug.apk", "feature/build/outputs/apk/release/feature-release.apk")) {
            assertFalse(hasClass(apk, "Lappmonitor/sdk/core/RealCore;"), apk)
            assertFalse(hasClass(apk, "Lappmonitor/sdk/core/NoOpCore;"), apk)
        }
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

// The SDK's projects in this repository and the artifacts they publish (ADR-0054).
private val SDK =
    listOf(
        ":sdk:core" to "appmonitor-core",
        ":sdk:core-no-op" to "appmonitor-core-no-op",
        ":sdk:android-okhttp" to "appmonitor-okhttp",
        ":sdk:okhttp-no-op" to "appmonitor-okhttp-no-op",
        ":sdk:ktor" to "appmonitor-ktor",
        ":sdk:ktor-no-op" to "appmonitor-ktor-no-op",
    )
