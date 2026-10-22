plugins {
    id("com.diffplug.spotless")
}

val ktlintVersion =
    versionCatalogs
        .named("libs")
        .findVersion("ktlint")
        .get()
        .requiredVersion

spotless {
    kotlin {
        target("src/**/*.kt")
        targetExclude("**/build/**")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion)
    }
}
