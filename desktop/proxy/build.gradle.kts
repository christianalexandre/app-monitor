import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("appmonitor.kotlin-jvm")
}

dependencies {
    api(project(":desktop:storage"))
    implementation(libs.netty.codec.http)
    implementation(libs.netty.handler)
    implementation(libs.bouncycastle.bcpkix)

    testImplementation(kotlin("test"))
}

tasks.test {
    testLogging.exceptionFormat = TestExceptionFormat.FULL
}
