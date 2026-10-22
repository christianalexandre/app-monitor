plugins {
    kotlin("multiplatform")
    id("appmonitor.lint")
}

kotlin {
    jvmToolchain(21)
    jvm()
    iosArm64()
    iosSimulatorArm64()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
