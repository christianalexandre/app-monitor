plugins {
    id("appmonitor.kmp-sdk-ios")
    id("appmonitor.publish")
}

kotlin {
    android {
        namespace = "appmonitor.sdk.core"
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
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
        }
        jvmTest.dependencies {
            implementation(project(":desktop:server"))
        }
    }
}

// ADR-0067: the simulator test runs under `simctl spawn`, which passes on SIMCTL_CHILD_ variables only.
tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>("iosSimulatorArm64Test") {
    val liveHost = providers.environmentVariable("APPMONITOR_LIVE_HOST").orNull
    inputs.property("liveHost", liveHost.orEmpty())
    if (liveHost != null) environment("SIMCTL_CHILD_APPMONITOR_LIVE_HOST", liveHost)
}
