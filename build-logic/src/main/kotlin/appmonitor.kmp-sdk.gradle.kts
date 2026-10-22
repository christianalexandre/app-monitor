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

// ADR-0023: SDK modules run on Android and the JVM only; iOS is the Swift SDK (ADR-0003).
kotlin {
    explicitApi()
    jvmToolchain(21)
    jvm()
    android {
        compileSdk = catalogVersion("android-compileSdk")
        minSdk = catalogVersion("android-sdk-minSdk")
        withHostTestBuilder {}
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
