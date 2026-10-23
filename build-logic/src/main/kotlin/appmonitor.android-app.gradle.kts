plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("appmonitor.lint")
}

val catalog = versionCatalogs.named("libs")

fun catalogVersion(name: String): Int =
    catalog
        .findVersion(name)
        .get()
        .requiredVersion
        .toInt()

android {
    compileSdk = catalogVersion("android-compileSdk")
    defaultConfig {
        minSdk = catalogVersion("android-sdk-minSdk")
        targetSdk = catalogVersion("android-compileSdk")
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}
