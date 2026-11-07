import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("appmonitor.compose-desktop")
}

dependencies {
    implementation(project(":desktop:data"))
    implementation(project(":desktop:designsystem"))
    implementation(project(":desktop:feature:breakpoints"))
    implementation(project(":desktop:feature:insights"))
    implementation(project(":desktop:feature:mocks"))
    implementation(project(":desktop:feature:settings"))
    implementation(project(":desktop:feature:traffic"))
    implementation(project(":desktop:mcp"))
    implementation(project(":desktop:proxy"))
    implementation(project(":desktop:server"))
    implementation(project(":desktop:storage"))
    implementation(project(":desktop:ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.jna)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":tools:fake-client"))
    testImplementation(libs.ktor.client.cio)
    testImplementation(testFixtures(project(":desktop:mcp")))
}

compose.desktop {
    application {
        mainClass = "appmonitor.desktop.app.MainKt"
        jvmArgs += "-Dappmonitor.version=${providers.gradleProperty("VERSION_NAME").get()}"

        // ADR-0055: bundleID and upgradeUuid are kept once released.
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // ADR-0105: jdk.crypto.ec holds JDK 21's EC provider, which the proxy's keys and TLS need; jdeps cannot see it.
            modules("java.instrument", "java.management", "java.sql", "jdk.crypto.ec", "jdk.unsupported")
            packageName = "App Monitor"
            packageVersion = "1.0.0"
            description = "Network monitor and debug toolkit for mobile apps"
            vendor = "Christian Alexandre"
            // ADR-0211: rendered from icon/app-monitor.svg by `appIcons`.
            macOS {
                iconFile = file("icon/app-monitor.icns")
                bundleID = "io.github.christianalexandre.appmonitor.desktop"
            }
            windows {
                iconFile = file("icon/app-monitor.ico")
                upgradeUuid = "8f77d24f-b78d-495b-80a0-9139348c95b8"
                menuGroup = "App Monitor"
            }
            linux {
                iconFile = file("src/main/resources/app-monitor.png")
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
    exclude("**/TitleBarScreenshots*")
    exclude("**/AppIconExport*")
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

// ADR-0178: opens a real window on macOS.
tasks.register<Test>("titleBarScreenshots") {
    description = "Picks Dark and Light in a real window and writes what AppKit draws of its title bar."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("appmonitor.desktop.app.TitleBarScreenshots")
    outputs.upToDateWhen { false }
    systemProperty("titleBarScreenshotDir", rootProject.file("docs/screenshots").absolutePath)
}

// ADR-0211: run by hand after editing icon/app-monitor.svg; commit what it writes.
tasks.register<Test>("appIcons") {
    description = "Renders icon/app-monitor.svg to the PNG, ICNS and ICO files the app and its packages use."
    group = "build"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("appmonitor.desktop.app.AppIconExport")
    outputs.upToDateWhen { false }
    systemProperty("appIconDir", file("icon").absolutePath)
    systemProperty("appIconPng", file("src/main/resources/app-monitor.png").absolutePath)
}
