import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    id("appmonitor.android-app")
}

android {
    namespace = "appmonitor.sample"
    defaultConfig {
        applicationId = "appmonitor.sample"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.okhttp)
    debugImplementation(project(":sdk:android-okhttp"))
    releaseImplementation(project(":sdk:okhttp-no-op"))
    testImplementation(kotlin("test-junit"))
}

fun projectPaths(root: ResolvedComponentResult): Set<String> {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (seen.add(component)) {
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
        }
    }
    return seen.mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }.toSet()
}

// ADR-0030, ADR-0072: the release APK ships the no-op modules, never the real SDK.
val checkReleaseHasNoSdk by tasks.registering {
    val paths =
        configurations
            .named("releaseRuntimeClasspath")
            .flatMap { it.incoming.resolutionResult.rootComponent }
            .map(::projectPaths)
    inputs.property("projects", paths)
    doLast {
        val leaked = paths.get().filter { it.startsWith(":sdk:") && !it.endsWith("-no-op") }
        check(leaked.isEmpty()) { "release runtime classpath contains $leaked" }
    }
}
tasks.named("check") { dependsOn(checkReleaseHasNoSdk) }
