import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("appmonitor.kotlin-jvm")
    `java-test-fixtures`
}

dependencies {
    // ADR-0133: tools reach the store through `desktop/domain` only.
    api(project(":desktop:domain"))
    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.serialization.json)

    testFixturesImplementation(kotlin("test"))
    testFixturesImplementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.ktor.client.cio)
    testImplementation(project(":tools:fake-client"))
    testImplementation(project(":desktop:data"))
    testImplementation(project(":desktop:storage"))
}

tasks.test {
    testLogging.exceptionFormat = TestExceptionFormat.FULL
}
