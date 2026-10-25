plugins {
    id("appmonitor.compose-desktop")
}

// ADR-0133: a feature sees the domain, desktop/ui and the design system only; desktop/app wires the repositories.
dependencies {
    implementation(project(":desktop:domain"))
    implementation(project(":desktop:designsystem"))
    implementation(project(":desktop:ui"))
    api(libs.lifecycle.viewmodel.compose)
    api(libs.koin.compose.viewmodel)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
