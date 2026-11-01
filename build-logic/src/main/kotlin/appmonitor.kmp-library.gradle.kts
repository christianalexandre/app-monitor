import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

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
    // ADR-0169: an app on Kotlin 2.2 compiles against the published modules.
    val floor = KotlinVersion.fromVersion(catalog.findVersion("sdk-kotlin-api").get().requiredVersion)
    coreLibrariesVersion = catalog.findVersion("sdk-kotlin-stdlib").get().requiredVersion
    compilerOptions {
        languageVersion.set(floor)
        apiVersion.set(floor)
    }
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
