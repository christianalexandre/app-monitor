plugins {
    id("appmonitor.compose-desktop")
}

// ADR-0133: Material stays behind this module; screens see only its tokens and components.
dependencies {
    api(libs.compose.foundation)
    implementation(libs.compose.material)

    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.ui.test)
}

tasks.test {
    val screenshots = layout.buildDirectory.dir("screenshots")
    outputs.dir(screenshots)
    systemProperty("screenshotDir", screenshots.get().asFile.absolutePath)
}
