plugins {
    id("appmonitor.kotlin-jvm")
    application
}

application {
    mainClass = "appmonitor.tools.fakeclient.MainKt"
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.jmdns)

    testImplementation(kotlin("test"))
    testImplementation(project(":desktop:server"))
}

tasks.processResources {
    from(rootProject.layout.projectDirectory.dir("protocol/fixtures")) { into("fixtures") }
}
