plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("appmonitor.lint")
}

// ADR-0062 D.1: the build type Xcode asks for, read as embedAndSignAppleFrameworkForXcode reads it.
fun String?.nativeBuildType() = this?.lowercase()?.takeIf { it == "debug" || it == "release" }

val xcodeBuildType =
    providers.environmentVariable("CONFIGURATION").orNull.nativeBuildType()
        ?: providers.environmentVariable("KOTLIN_FRAMEWORK_BUILD_TYPE").orNull.nativeBuildType()
val sdkKtor = if (xcodeBuildType == "release") ":sdk:ktor-no-op" else ":sdk:ktor"

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
            implementation(project(sdkKtor))
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
