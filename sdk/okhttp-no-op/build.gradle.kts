plugins {
    id("appmonitor.kmp-sdk")
    id("appmonitor.publish")
}

kotlin {
    android {
        namespace = "appmonitor.sdk.okhttp.noop"
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":sdk:core-no-op"))
            api(libs.okhttp)
        }
    }
}
