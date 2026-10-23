plugins {
    id("appmonitor.kmp-sdk")
}

kotlin {
    android {
        namespace = "appmonitor.sdk.noop"
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.okhttp)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":protocol"))
        }
    }
}

// ADR-0026: the API test compares this module's reference dumps with the real SDK's.
val sdkDir = rootDir.resolve("sdk")
tasks.withType<Test>().configureEach {
    inputs.files(fileTree(sdkDir) { include("*/api/**/*.api") }).withPropertyName("apiDumps")
    systemProperty("appmonitor.sdkDir", sdkDir.absolutePath)
}
