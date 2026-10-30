package appmonitor.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.DependencySubstitutions
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

internal const val GROUP = "io.github.christianalexandre"

/** Set by this repository's `gradle.properties`: the SDK comes from its projects, not from Maven (ADR-0157). */
internal const val LOCAL_SDK_PROPERTY = "appmonitor.sdk.local"

/** One SDK module with its no-op twin (ADR-0072): the published artifact and this repository's project. */
internal enum class SdkModule(
    val artifact: String,
    val path: String,
) {
    CORE("appmonitor-core", ":sdk:core"),
    OKHTTP("appmonitor-okhttp", ":sdk:android-okhttp"),
    KTOR("appmonitor-ktor", ":sdk:ktor"),
    ;

    val noOpArtifact get() = "$artifact-no-op"
    val noOpPath get() = path.replace(Regex(":sdk:(android-)?"), ":sdk:") + "-no-op"
}

internal class Sdk(
    private val project: Project,
) {
    private val local = project.providers.gradleProperty(LOCAL_SDK_PROPERTY).orNull == "true"

    /** Adds [module] to [configuration]; [excludes] keeps the app's own version of a library the adapter wraps. */
    fun add(
        configuration: String,
        module: SdkModule,
        vararg excludes: Pair<String, String>,
    ) {
        val notation: Any =
            if (local) {
                project.dependencies.project(mapOf("path" to module.path))
            } else {
                "$GROUP:${module.artifact}:$SDK_VERSION"
            }
        val dependency = project.dependencies.add(configuration, notation) as ModuleDependency
        excludes.forEach { (group, name) -> dependency.exclude(mapOf("group" to group, "module" to name)) }
    }

    /** Swaps every real module for its no-op in [configuration], whichever module of the build asked for it. */
    fun swapForNoOps(configuration: Configuration) {
        configuration.resolutionStrategy.dependencySubstitution { substitutions ->
            SdkModule.entries.forEach { sdk ->
                substitutions.substitute(substitutions.selector(sdk, noOp = false)).using(substitutions.selector(sdk, noOp = true))
            }
        }
    }

    /** Leaves every SDK module, real or no-op, out of [configuration]; other modules of [GROUP] stay. */
    fun exclude(configuration: Configuration) {
        SdkModule.entries
            .flatMap {
                if (local) {
                    listOf(it.path, it.noOpPath).map { path ->
                        path.substringAfterLast(':')
                    }
                } else {
                    listOf(it.artifact, it.noOpArtifact)
                }
            }.forEach { configuration.exclude(mapOf("group" to GROUP, "module" to it)) }
    }

    private fun DependencySubstitutions.selector(
        sdk: SdkModule,
        noOp: Boolean,
    ): ComponentSelector =
        if (local) {
            this.project(if (noOp) sdk.noOpPath else sdk.path)
        } else {
            // A substitution target needs a version; the requested side matches any.
            if (noOp) this.module("$GROUP:${sdk.noOpArtifact}:$SDK_VERSION") else this.module("$GROUP:${sdk.artifact}")
        }
}

// A KMP module publishes per-target artifacts (`appmonitor-core-android`, `appmonitor-core-iosarm64`, …).
private val REAL_ARTIFACT = Regex("appmonitor-(core|okhttp|ktor)(-[a-z0-9]+)?")

/** Every component in the graph under [root], each once. */
internal fun components(root: ResolvedComponentResult): Sequence<ResolvedComponentResult> =
    sequence {
        val seen = mutableSetOf<ResolvedComponentResult>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!seen.add(component)) continue
            yield(component)
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
        }
    }

/** The real SDK modules in the graph under [root]; a release build must have none (ADR-0157). */
internal fun realSdkIn(root: ResolvedComponentResult): List<String> {
    val realPaths = SdkModule.entries.map { it.path }.toSet()
    return components(root)
        .mapNotNull { component ->
            when (val id = component.id) {
                is ModuleComponentIdentifier -> {
                    id.displayName.takeIf { id.group == GROUP && REAL_ARTIFACT.matches(id.module) && !id.module.contains("-no-op") }
                }

                is ProjectComponentIdentifier -> {
                    id.projectPath.takeIf { it in realPaths }
                }

                else -> {
                    null
                }
            }
        }.toList()
}
