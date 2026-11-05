package appmonitor.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A KMP consumer that applies only the plugin, its `:sdk:ktor` and no-op stood in by projects that print which one
 * linked, built for the host's native target: a release binary links the no-ops whatever Xcode's environment or the
 * requested tasks say (ADR-0161).
 */
class KmpReleaseLinkTest {
    private val dir = File("build/kmp-consumer").absoluteFile.apply { deleteRecursively() }
    private val host =
        System.getProperty("os.name").lowercase().let { os ->
            when {
                "mac" in os && System.getProperty("os.arch") == "aarch64" -> "macosArm64"
                "mac" in os -> "macosX64"
                "windows" in os -> "mingwX64"
                else -> "linuxX64"
            }
        }

    private fun write(
        path: String,
        text: String,
    ) = dir.resolve(path).apply { parentFile.mkdirs() }.writeText(text.trimIndent())

    private fun kmp(
        path: String,
        extra: String = "",
    ) = write(
        "$path/build.gradle.kts",
        """
        plugins { kotlin("multiplatform") }
        kotlin { $host() }
        $extra
        """,
    )

    private fun consumer() {
        write("gradle.properties", "org.gradle.jvmargs=-Xmx2g\nappmonitor.sdk.local=true")
        write(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { mavenCentral() } }
            dependencyResolutionManagement { repositories { mavenCentral(); maven(url = uri("repo")) } }
            include(":sdk:core", ":sdk:core-no-op", ":sdk:android-okhttp", ":sdk:okhttp-no-op", ":sdk:ktor", ":sdk:ktor-no-op", ":app")
            """,
        )
        // The swap names every SDK project; only Ktor's are on this consumer's graph.
        listOf("sdk/core", "sdk/core-no-op", "sdk/android-okhttp", "sdk/okhttp-no-op").forEach { kmp(it) }
        for ((path, name) in listOf("sdk/ktor" to "real", "sdk/ktor-no-op" to "no-op")) {
            kmp(path)
            write("$path/src/commonMain/kotlin/Sdk.kt", "package appmonitor.sdk.ktor\nobject Sdk { fun name() = \"$name\" }")
        }
        write(
            "app/build.gradle.kts",
            """
            plugins {
                kotlin("multiplatform")
                id("io.github.christianalexandre.appmonitor")
            }
            kotlin { $host { binaries.executable() } }
            """,
        )
        write("app/src/commonMain/kotlin/Main.kt", "fun main() = println(appmonitor.sdk.ktor.Sdk.name())")
    }

    private fun runner(
        env: Map<String, String>,
        vararg tasks: String,
    ) = GradleRunner
        .create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withEnvironment(System.getenv() + env)
        .withArguments(*tasks, "--stacktrace")
        .forwardOutput()

    private fun build(
        env: Map<String, String>,
        vararg tasks: String,
    ) = runner(env, *tasks).build()

    /** What the [buildType] executable prints: the SDK it linked. */
    private fun run(buildType: String): String {
        val exe = dir.resolve("app/build/bin/$host/${buildType}Executable/app.${if (host == "mingwX64") "exe" else "kexe"}")
        val process = ProcessBuilder(exe.path).redirectErrorStream(true).start()
        val out =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        check(process.waitFor() == 0) { out }
        return out
    }

    @Test
    fun `a release link takes the no-ops when Xcode's environment says debug`() {
        consumer()
        // Kotlin links Release here: CONFIGURATION wins over KOTLIN_FRAMEWORK_BUILD_TYPE.
        val xcode = mapOf("CONFIGURATION" to "Release", "KOTLIN_FRAMEWORK_BUILD_TYPE" to "debug")

        build(xcode, ":app:linkDebugExecutable${host.cap()}", ":app:linkReleaseExecutable${host.cap()}")

        assertEquals("real", run("debug"))
        assertEquals("no-op", run("release"))
    }

    @Test
    fun `assemble links debug with the SDK and release with the no-ops, in a configuration Kotlin cannot name`() {
        consumer()

        build(mapOf("CONFIGURATION" to "Dev"), ":app:assemble")

        assertEquals("real", run("debug"))
        assertEquals("no-op", run("release"))
    }

    @Test
    fun `fails a release link that resolves a real SDK artifact`() {
        consumer()
        // A per-target artifact the swap does not name, declared by hand.
        val artifact = "appmonitor-ktor-${host.lowercase()}"
        val module = dir.resolve("repo/io/github/christianalexandre/$artifact/1.0").apply { mkdirs() }
        module.resolve("$artifact-1.0.pom").writeText(
            """
            <project><modelVersion>4.0.0</modelVersion><groupId>io.github.christianalexandre</groupId>
            <artifactId>$artifact</artifactId><version>1.0</version><packaging>klib</packaging></project>
            """.trimIndent(),
        )
        module.resolve("$artifact-1.0.klib").writeText("")
        dir.resolve("app/build.gradle.kts").appendText(
            "\nkotlin { sourceSets.commonMain.dependencies { implementation(\"io.github.christianalexandre:$artifact:1.0\") } }",
        )

        val result = runner(emptyMap(), ":app:linkReleaseExecutable${host.cap()}").buildAndFail()

        assertTrue("resolves the real SDK" in result.output, result.output)
        assertTrue(artifact in result.output, result.output)
    }

    private fun String.cap() = replaceFirstChar(Char::uppercase)
}
