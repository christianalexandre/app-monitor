plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("appmonitor.lint")
}

val catalog = versionCatalogs.named("libs")

fun catalogVersion(name: String): Int =
    catalog
        .findVersion(name)
        .get()
        .requiredVersion
        .toInt()

kotlin {
    jvmToolchain(21)
    jvm()
    android {
        compileSdk = catalogVersion("android-compileSdk")
        minSdk = catalogVersion("android-minSdk")
        withHostTestBuilder {}
    }
    iosArm64()
    iosSimulatorArm64()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
