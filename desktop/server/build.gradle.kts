plugins {
    id("appmonitor.kotlin-jvm")
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
