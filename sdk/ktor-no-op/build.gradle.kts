plugins {
    id("appmonitor.kmp-sdk-ios")
    id("appmonitor.publish")
}

kotlin {
    android {
        namespace = "appmonitor.sdk.ktor.noop"
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":sdk:core-no-op"))
            api(libs.ktor.client.core)
        }
    }
}
