package appmonitor.gradle

import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * `io.github.christianalexandre.appmonitor`: adds the SDK to the module and swaps it for its no-ops outside debug
 * builds (ADR-0093, ADR-0157); rewrites OkHttp's `OkHttpClient(builder)` in an app's debuggable variants (ADR-0112).
 */
public class AppMonitorPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("appMonitor", AppMonitorExtension::class.java)
        extension.injectOkHttp.convention(true)
        val sdk = Sdk(project)
        project.pluginManager.withPlugin("com.android.application") {
            sdk.add("implementation", SdkModule.CORE)
            sdk.add("implementation", SdkModule.OKHTTP, "com.squareup.okhttp3" to "okhttp")
            val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            components.onVariants(components.selector().all()) { variant ->
                if (!variant.debuggable) {
                    sdk.swapForNoOps(variant.runtimeConfiguration)
                    sdk.swapForNoOps(variant.compileConfiguration)
                    val check = registerCheck(project, variant.name, variant.runtimeConfiguration.name)
                    project.tasks
                        .matching { it.name == "pre${variant.name.replaceFirstChar(Char::uppercase)}Build" }
                        .configureEach { it.dependsOn(check) }
                    return@onVariants
                }
                if (!extension.injectOkHttp.get()) return@onVariants
                val graph = variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
                variant.instrumentation.transformClassesWith(OkHttpInjection::class.java, InstrumentationScope.ALL) {
                    it.sdkOnClasspath.set(graph.map(::hasSdk))
                }
            }
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            sdk.add("commonMainImplementation", SdkModule.KTOR)
            val buildType = nativeBuildType(project)
            if (buildType == null || buildType == "debug") return@withPlugin
            project.configurations.configureEach { sdk.swapForNoOps(it) }
            val klibs =
                project.configurations.matching {
                    it.isCanBeResolved && it.name.endsWith("CompileKlibraries") && !it.name.contains("Test")
                }
            klibs.all { configuration ->
                val check = registerCheck(project, configuration.name, configuration.name)
                val target = configuration.name.removeSuffix("CompileKlibraries").replaceFirstChar(Char::uppercase)
                project.tasks
                    .matching { it.name.startsWith("link") && it.name.endsWith(target) }
                    .configureEach { it.dependsOn(check) }
            }
        }
    }

    private fun registerCheck(
        project: Project,
        name: String,
        configuration: String,
    ) = project.tasks.register("appMonitorCheck${name.replaceFirstChar(Char::uppercase)}", NoSdkCheck::class.java) { task ->
        task.configurationName.set(configuration)
        task.found.set(
            project.configurations
                .named(configuration)
                .flatMap { it.incoming.resolutionResult.rootComponent }
                .map(::realSdkIn),
        )
    }
}

/**
 * The build type Xcode asks the Kotlin framework for, as `embedAndSignAppleFrameworkForXcode` reads it. Outside Xcode,
 * `release` when a requested task builds or publishes a release binary or an XCFramework, else null. Anything but
 * `debug` gets the no-ops (ADR-0157), so a configuration Kotlin cannot name never links the SDK.
 */
internal fun nativeBuildType(project: Project): String? {
    val env = project.providers
    val xcode = env.environmentVariable("KOTLIN_FRAMEWORK_BUILD_TYPE").orNull ?: env.environmentVariable("CONFIGURATION").orNull
    if (xcode != null) return xcode.lowercase()
    val tasks =
        project.gradle.startParameter.taskNames
            .map { it.substringAfterLast(':').lowercase() }
    return if (tasks.any { "release" in it || "xcframework" in it || "publish" in it }) "release" else null
}

/** Fails a release build that still resolves a real SDK module, whoever declared it (ADR-0157). */
public abstract class NoSdkCheck : DefaultTask() {
    @get:Input
    public abstract val configurationName: Property<String>

    @get:Input
    public abstract val found: ListProperty<String>

    @TaskAction
    public fun check() {
        val found = found.get()
        if (found.isNotEmpty()) {
            throw GradleException(
                "AppMonitor: ${configurationName.get()} resolves the real SDK ($found) in a release build; remove that dependency (ADR-0157)",
            )
        }
    }
}
