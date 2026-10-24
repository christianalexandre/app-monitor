plugins {
    id("appmonitor.kmp-sdk-ios")
    id("appmonitor.publish")
}

kotlin {
    android {
        namespace = "appmonitor.sdk.core.noop"
    }
}
