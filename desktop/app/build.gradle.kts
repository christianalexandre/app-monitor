plugins {
    id("appmonitor.compose-desktop")
}

dependencies {
    implementation(project(":desktop:server"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.compose.ui.test)
    testImplementation(project(":tools:fake-client"))
}

compose.desktop {
    application {
        mainClass = "appmonitor.desktop.app.MainKt"
    }
}

tasks.test {
    val screenshots = layout.buildDirectory.dir("screenshots")
    outputs.dir(screenshots)
    systemProperty("screenshotDir", screenshots.get().asFile.absolutePath)
}

tasks.test {
    exclude("**/LiveDeviceScreenshots*")
}

// Needs a device running the sample (ADR-0031).
tasks.register<Test>("liveScreenshots") {
    description = "Approves a real device and screenshots its traffic in the desktop UI."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("appmonitor.desktop.app.LiveDeviceScreenshots")
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
    systemProperty("liveScreenshotDir", rootProject.file("docs/screenshots").absolutePath)
    listOf("liveName", "liveExchanges", "liveTimeoutMs", "liveAdvertise").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
