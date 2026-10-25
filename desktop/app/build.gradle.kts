import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("appmonitor.compose-desktop")
}

dependencies {
    implementation(project(":desktop:data"))
    implementation(project(":desktop:designsystem"))
    implementation(project(":desktop:feature:breakpoints"))
    implementation(project(":desktop:feature:mocks"))
    implementation(project(":desktop:feature:traffic"))
    implementation(project(":desktop:mcp"))
    implementation(project(":desktop:proxy"))
    implementation(project(":desktop:server"))
    implementation(project(":desktop:storage"))
    implementation(project(":desktop:ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.compose.ui.test)
    testImplementation(project(":tools:fake-client"))
    testImplementation(libs.ktor.client.cio)
    testImplementation(testFixtures(project(":desktop:mcp")))
}

compose.desktop {
    application {
        mainClass = "appmonitor.desktop.app.MainKt"

        // ADR-0055: bundleID and upgradeUuid are kept once released.
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // ADR-0105: jdk.crypto.ec holds JDK 21's EC provider, which the proxy's keys and TLS need; jdeps cannot see it.
            modules("java.instrument", "java.management", "java.sql", "jdk.crypto.ec", "jdk.unsupported")
            packageName = "App Monitor"
            packageVersion = "1.0.0"
            description = "Network monitor and debug toolkit for mobile apps"
            vendor = "Christian Alexandre"
            macOS {
                bundleID = "io.github.christianalexandre.appmonitor.desktop"
            }
            windows {
                upgradeUuid = "8f77d24f-b78d-495b-80a0-9139348c95b8"
                menuGroup = "App Monitor"
            }
            linux {
                packageName = "app-monitor"
            }
        }
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
    listOf("liveName", "liveExchanges", "liveTimeoutMs", "liveAdvertise", "liveDataDir").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
