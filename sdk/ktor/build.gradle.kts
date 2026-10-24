plugins {
    id("appmonitor.kmp-sdk-ios")
}

// Compiled into commonTest, so the JVM, the Android host and the simulator read the same vector (ADR-0036 A).
val generateMaskingVector by tasks.registering {
    val vector = rootProject.layout.projectDirectory.file("protocol/fixtures/masking.json")
    val output = layout.buildDirectory.dir("generated/maskingVector")
    inputs.file(vector).withPropertyName("maskingVector").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(output)
    doLast {
        val json = vector.asFile.readText()
        check("\"\"\"" !in json && "$" !in json) { "masking.json cannot be embedded in a raw string" }
        val file = output.get().file("appmonitor/sdk/ktor/MaskingVector.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package appmonitor.sdk.ktor\n\ninternal const val MASKING_VECTOR: String = \"\"\"$json\"\"\"\n")
    }
}

kotlin {
    android {
        namespace = "appmonitor.sdk.ktor"
    }
    sourceSets {
        all {
            languageSettings.optIn("appmonitor.sdk.core.InternalAppMonitorApi")
        }
        commonMain.dependencies {
            api(project(":sdk:core"))
            api(libs.ktor.client.core)
            implementation(project(":protocol"))
        }
        commonTest {
            kotlin.srcDir(generateMaskingVector)
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
