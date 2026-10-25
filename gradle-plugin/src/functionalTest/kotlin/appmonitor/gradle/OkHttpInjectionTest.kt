package appmonitor.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Builds a consumer app with AGP and reads its APKs with `dexdump`, the plugin applied from its classpath. */
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
            dependencies {
                implementation("com.squareup.okhttp3:okhttp:$okhttp")
            }
            $extra
            """,
        )
        write("$name/src/main/AndroidManifest.xml", "<manifest />")
    }

    /** The calls to the entry point in an APK's code. */
    private fun installCalls(apk: String): Int {
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
        return Regex("""invoke-static \{v\d+}, Lappmonitor/sdk/okhttp/AppMonitorOkHttp;\.install:""").findAll(out).count()
    }

    @Test
    fun `rewrites OkHttp in debuggable variants that have the SDK`() {
        write("local.properties", "sdk.dir=${sdk.path}")
        write("gradle.properties", "android.builtInKotlin=false\norg.gradle.jvmargs=-Xmx2g")
        write(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { google(); mavenCentral() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            include(":android-okhttp", ":app", ":optout", ":bare")
            """,
        )
        write(
            "android-okhttp/build.gradle.kts",
            """
            plugins { `java-library` }
            group = "io.github.christianalexandre"
            dependencies { compileOnly("com.squareup.okhttp3:okhttp:$okhttp") }
            tasks.withType<JavaCompile>().configureEach { options.release = 11 }
            """,
        )
        write(
            "android-okhttp/src/main/java/appmonitor/sdk/okhttp/AppMonitorOkHttp.java",
            """
            package appmonitor.sdk.okhttp;
            public final class AppMonitorOkHttp {
                public static void install(okhttp3.OkHttpClient.Builder builder) {}
            }
            """,
        )
        app("app", """dependencies { debugImplementation(project(":android-okhttp")) }""")
        app("optout", """dependencies { debugImplementation(project(":android-okhttp")) }""" + "\nappMonitor { injectOkHttp = false }")
        app("bare", "")

        GradleRunner
            .create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(":app:assembleDebug", ":app:assembleRelease", ":optout:assembleDebug", ":bare:assembleDebug", "--stacktrace")
            .forwardOutput()
            .build()

        assertEquals(1, installCalls("app/build/outputs/apk/debug/app-debug.apk"))
        assertEquals(0, installCalls("app/build/outputs/apk/release/app-release-unsigned.apk"))
        assertEquals(0, installCalls("optout/build/outputs/apk/debug/optout-debug.apk"))
        assertEquals(0, installCalls("bare/build/outputs/apk/debug/bare-debug.apk"))
        assertTrue(dir.resolve("bare/build/outputs/apk/debug/bare-debug.apk").isFile)
    }
}
