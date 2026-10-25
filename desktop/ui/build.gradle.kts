plugins {
    id("appmonitor.kotlin-jvm")
}

// ADR-0137: what more than one feature shows of the domain; no feature, data or store.
dependencies {
    api(project(":desktop:domain"))

    testImplementation(kotlin("test"))
}
