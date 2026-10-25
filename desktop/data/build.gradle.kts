plugins {
    id("appmonitor.kotlin-jvm")
}

// ADR-0133: the only path from a feature to the store; the store's types stay here.
dependencies {
    api(project(":desktop:domain"))
    implementation(project(":desktop:storage"))

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
