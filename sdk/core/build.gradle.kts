plugins {
    id("appmonitor.kmp-sdk")
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(project(":desktop:server"))
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
        }
    }
}
