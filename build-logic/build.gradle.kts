plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.compose.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.android.gradle.plugin)
    implementation(libs.wire.gradle.plugin)
}
