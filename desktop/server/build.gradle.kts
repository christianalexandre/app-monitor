plugins {
    id("appmonitor.kotlin-jvm")
    application
}

application {
    mainClass = "appmonitor.desktop.server.MainKt"
}

dependencies {
    api(project(":protocol"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.jmdns)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.cio)
}

tasks.test {
    // DEBT-0046: hosted macOS runners deny the test process local network access.
    if (providers.environmentVariable("APPMONITOR_NO_LOCAL_NETWORK").orNull == "1") {
        filter { excludeTestsMatching("*resolves over mdns") }
    }
}
