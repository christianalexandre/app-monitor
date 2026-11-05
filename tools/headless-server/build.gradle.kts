plugins {
    id("appmonitor.kotlin-jvm")
    application
}

application {
    mainClass = "appmonitor.tools.headlessserver.MainKt"
}

dependencies {
    implementation(project(":desktop:server"))
    implementation(project(":desktop:proxy"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}
