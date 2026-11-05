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
    testImplementation(project(":desktop:server"))
    testImplementation(project(":desktop:data"))
    testImplementation(project(":desktop:storage"))
}

// DEBT-0122: the curl the tests run is one of their inputs, so a new one is not hidden by the cache.
val curl = providers.provider { "curl" }
val curlVersion =
    providers
        .exec {
            commandLine(curl.get(), "--version")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.lineSequence().first() }

tasks.test {
    testLogging.exceptionFormat = TestExceptionFormat.FULL
    inputs.property("curlVersion", curlVersion)
}
