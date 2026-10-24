plugins {
    id("appmonitor.kmp-sdk")
}

// ADR-0062 E.1, ADR-0067: SDK modules a KMP app links on iOS; OkHttp ones stay on appmonitor.kmp-sdk.
kotlin {
    iosArm64()
    iosSimulatorArm64()
}
