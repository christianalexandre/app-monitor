plugins {
    id("appmonitor.kotlin-jvm")
}

// ADR-0133, ADR-0135: no Compose, no SQLDelight, no Ktor.
dependencies {
    api(project(":protocol"))
    api(libs.kotlinx.coroutines.core)
}
