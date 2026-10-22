plugins {
    kotlin("jvm")
    id("appmonitor.lint")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
