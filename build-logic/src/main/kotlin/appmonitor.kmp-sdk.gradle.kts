import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

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

// ADR-0023: Android and the JVM; appmonitor.kmp-sdk-ios adds iOS (ADR-0067).
kotlin {
    explicitApi()
    // ADR-0169: an app on Kotlin 2.2 compiles against the published modules.
    val floor = KotlinVersion.fromVersion(catalog.findVersion("sdk-kotlin-api").get().requiredVersion)
    coreLibrariesVersion = catalog.findVersion("sdk-kotlin-stdlib").get().requiredVersion
    compilerOptions {
        languageVersion.set(floor)
        apiVersion.set(floor)
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
    jvmToolchain(21)
    jvm()
    android {
        compileSdk = catalogVersion("android-compileSdk")
        minSdk = catalogVersion("android-sdk-minSdk")
        withHostTestBuilder {}
    }

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        filters {
            exclude {
                byNames.add("appmonitor.sdk.core.InternalAppMonitorApi")
                annotatedWith.add("appmonitor.sdk.core.InternalAppMonitorApi")
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
