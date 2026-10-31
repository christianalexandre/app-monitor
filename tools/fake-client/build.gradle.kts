plugins {
    id("appmonitor.kotlin-jvm")
    application
}

application {
    mainClass = "appmonitor.tools.fakeclient.MainKt"
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":desktop:server"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.jmdns)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
}

tasks.processResources {
    from(rootProject.layout.projectDirectory.dir("protocol/fixtures")) { into("fixtures") }
}
