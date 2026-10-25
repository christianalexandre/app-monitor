import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("appmonitor.kotlin-jvm")
}

dependencies {
    api(project(":desktop:storage"))
    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.ktor.client.cio)
}

tasks.test {
    testLogging.exceptionFormat = TestExceptionFormat.FULL
}
