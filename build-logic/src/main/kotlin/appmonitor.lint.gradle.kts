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
        // A pattern walks the module dir, build/ included, and fails on files a parallel compile is writing.
        target(fileTree("src") { include("**/*.kt") })
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion)
    }
}
