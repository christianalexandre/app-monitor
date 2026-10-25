import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    id("appmonitor.kmp-sdk-ios")
    id("appmonitor.publish")
}

// Compiled into commonTest, so the JVM, the Android host and the simulator read the same vector (ADR-0036 A).
val generateMockMatchingVector by tasks.registering {
    val vector = rootProject.layout.projectDirectory.file("protocol/fixtures/mock_matching.json")
    val output = layout.buildDirectory.dir("generated/mockMatchingVector")
    inputs.file(vector).withPropertyName("mockMatchingVector").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(output)
    doLast {
        val json = vector.asFile.readText()
        check("\"\"\"" !in json && "$" !in json) { "mock_matching.json cannot be embedded in a raw string" }
        val file = output.get().file("appmonitor/sdk/core/MockMatchingVector.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package appmonitor.sdk.core\n\ninternal const val MOCK_MATCHING_VECTOR: String = \"\"\"$json\"\"\"\n")
    }
}

// The app lint of samples/kmp reads the host test sources, generated directory included.
tasks.matching { it.name.endsWith("LintModel") || it.name.startsWith("lintAnalyze") }.configureEach {
    dependsOn(generateMockMatchingVector)
}

kotlin {
    android {
        namespace = "appmonitor.sdk.core"
    }
    // jvmAndAndroidMain: java.io code both targets share.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                withJvm()
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":protocol"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.io.core)
        }
        commonTest {
            kotlin.srcDir(generateMockMatchingVector)
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
            }
        }
        jvmTest.dependencies {
            implementation(project(":desktop:server"))
        }
    }
}

// ADR-0067: the simulator test runs under `simctl spawn`, which passes on SIMCTL_CHILD_ variables only.
tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>("iosSimulatorArm64Test") {
    for (name in listOf("APPMONITOR_LIVE_HOST", "APPMONITOR_LIVE_DISCOVERY")) {
        val value = providers.environmentVariable(name).orNull
        inputs.property(name, value.orEmpty())
        if (value != null) environment("SIMCTL_CHILD_$name", value)
    }
}
