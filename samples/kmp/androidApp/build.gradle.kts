import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    id("appmonitor.android-app")
    id("io.github.christianalexandre.appmonitor")
}

android {
    namespace = "appmonitor.sample.kmp"
    compileSdk =
        libs.versions.android.kmp.sample.compileSdk
            .get()
            .toInt()
    defaultConfig {
        applicationId = "appmonitor.sample.kmp"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":samples:kmp:shared"))
    implementation(libs.androidx.activity.compose)
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
        val all = paths.get()
        check(":sdk:ktor-no-op" in all) { "release runtime classpath has no :sdk:ktor-no-op: $all" }
        val leaked = all.filter { (it.startsWith(":sdk:") && !it.endsWith("-no-op")) || it == ":protocol" }
        check(leaked.isEmpty()) { "release runtime classpath contains $leaked" }
    }
}
tasks.named("check") { dependsOn(checkReleaseHasNoSdk) }
