plugins {
    id("appmonitor.kotlin-jvm")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(project(":protocol"))
    testImplementation(project(":sdk:core-no-op"))
}

// ADR-0072: the API test compares each no-op module's reference dumps with its real module's.
val sdkDir = rootDir.resolve("sdk")
tasks.withType<Test>().configureEach {
    inputs.files(fileTree(sdkDir) { include("*/api/**/*.api") }).withPropertyName("apiDumps")
    systemProperty("appmonitor.sdkDir", sdkDir.absolutePath)
}
