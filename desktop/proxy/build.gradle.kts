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
}

tasks.test {
    testLogging.exceptionFormat = TestExceptionFormat.FULL
}
