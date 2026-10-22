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
