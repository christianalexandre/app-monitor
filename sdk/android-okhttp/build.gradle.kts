plugins {
    id("appmonitor.kmp-sdk")
    id("appmonitor.publish")
}

kotlin {
    android {
        namespace = "appmonitor.sdk.okhttp"
    }
    sourceSets {
        all {
            languageSettings.optIn("appmonitor.sdk.core.InternalAppMonitorApi")
        }
        commonMain.dependencies {
            api(project(":sdk:core"))
            api(libs.okhttp)
            implementation(project(":protocol"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.okhttp.mockwebserver3)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

tasks.named<Test>("jvmTest") {
    val vector = rootProject.layout.projectDirectory.file("protocol/fixtures/masking.json")
    inputs.file(vector).withPropertyName("maskingVector").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("appmonitor.maskingVector", vector.asFile.absolutePath)
}
