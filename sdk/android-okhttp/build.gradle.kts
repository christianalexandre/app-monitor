plugins {
    id("appmonitor.kmp-sdk")
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
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.okhttp.mockwebserver3)
        }
    }
}
