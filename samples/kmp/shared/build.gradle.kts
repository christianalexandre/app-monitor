plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("appmonitor.lint")
    id("io.github.christianalexandre.appmonitor")
}

kotlin {
    jvmToolchain(21)
    android {
        namespace = "appmonitor.sample.kmp.shared"
        compileSdk =
            libs.versions.android.kmp.sample.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.sdk.minSdk
                .get()
                .toInt()
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.material)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
