plugins {
    id("appmonitor.compose-desktop")
}

// ADR-0133: a feature sees the domain and the design system only; desktop/app wires the repositories.
dependencies {
    implementation(project(":desktop:domain"))
    implementation(project(":desktop:designsystem"))
    implementation(project(":desktop:ui"))
    api(libs.lifecycle.viewmodel.compose)
    api(libs.koin.compose.viewmodel)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.ui.test)
}
