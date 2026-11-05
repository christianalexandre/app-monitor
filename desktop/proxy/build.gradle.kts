import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("appmonitor.kotlin-jvm")
}

dependencies {
    api(project(":desktop:storage"))
    implementation(libs.netty.codec.http)
    implementation(libs.netty.handler)
    implementation(libs.netty.codec.http2)
    implementation(libs.bouncycastle.bcpkix)

    testImplementation(kotlin("test"))
    testImplementation(libs.grpc.netty.shaded)
    testImplementation(libs.grpc.stub)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver3)
}

// DEBT-0122: the curl the tests run is one of their inputs, so a new one is not hidden by the cache.
val curl = providers.environmentVariable("APPMONITOR_CURL").filter { it.isNotBlank() }.orElse("curl")
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
