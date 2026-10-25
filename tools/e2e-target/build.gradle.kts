plugins {
    id("appmonitor.kotlin-jvm")
    application
}

application {
    mainClass = "appmonitor.tools.e2etarget.MainKt"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.stub)
    implementation(libs.bouncycastle.bcpkix)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.cio)
}
