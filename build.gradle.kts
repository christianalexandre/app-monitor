plugins {
    base
    id("appmonitor.lint")
}

spotless {
    kotlin {
        target("build-logic/src/**/*.kt", "gradle-plugin/src/**/*.kt")
    }
    kotlinGradle {
        target("*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts", "gradle-plugin/*.gradle.kts")
    }
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
    dependsOn(gradle.includedBuild("gradle-plugin").task(":check"))
}
