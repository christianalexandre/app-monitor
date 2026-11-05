package appmonitor.gradle

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.AbstractNativeLibrary
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import java.util.concurrent.Callable

/**
 * `io.github.christianalexandre.appmonitor`: adds the SDK to the module and swaps it for its no-ops outside debug
 * builds (ADR-0093, ADR-0157, ADR-0161); rewrites OkHttp's `OkHttpClient(builder)` in an app's debuggable variants (ADR-0112).
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
            // A feature resolves this app's dependencies unswapped; its classes come from this app's APK (ADR-0161).
            components.finalizeDsl { android ->
                android.dynamicFeatures.forEach { path ->
                    project
                        .project(path)
                        .configurations
                        .matching { it.name.endsWith("RuntimeClasspath") && "Test" !in it.name }
                        .configureEach(sdk::exclude)
                }
            }
            components.onVariants(components.selector().all()) { variant ->
                if (!variant.debuggable) {
                    sdk.swapForNoOps(variant.runtimeConfiguration)
                    sdk.swapForNoOps(variant.compileConfiguration)
                    val check = registerCheck(project, variant.name, variant.runtimeConfiguration.incoming.resolutionResult.rootComponent)
                    project.tasks
                        .matching { it.name == "pre${variant.name.replaceFirstChar(Char::uppercase)}Build" }
                        .configureEach { it.dependsOn(check) }
                    return@onVariants
                }
                if (!extension.injectOkHttp.get()) return@onVariants
                val graph = variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
                // ADR-0169: the rewrite calls the entry points of the plugin's own SDK version, none other.
                val version = graph.map { okHttpSdkVersion(it).orEmpty() }
                variant.instrumentation.transformClassesWith(OkHttpInjection::class.java, InstrumentationScope.ALL) {
                    it.sdkOnClasspath.set(version.map { found -> found == SDK_VERSION })
                }
                val check =
                    project.tasks.register(
                        "appMonitorOkHttpCheck${variant.name.replaceFirstChar(Char::uppercase)}",
                        OkHttpVersionCheck::class.java,
                    ) {
                        it.variantName.set(variant.name)
                        it.found.set(version)
                    }
                project.tasks
                    .matching { it.name == "pre${variant.name.replaceFirstChar(Char::uppercase)}Build" }
                    .configureEach { it.dependsOn(check) }
                variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS)
            }
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            sdk.add("commonMainImplementation", SdkModule.KTOR)
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            val noOpKlibs = mutableMapOf<String, Configuration>()
            kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach { target ->
                target.binaries.matching { it.buildType == NativeBuildType.RELEASE }.configureEach { binary ->
                    if (binary is AbstractNativeLibrary) {
                        project.configurations.named(binary.exportConfigurationName).configure(sdk::swapForNoOps)
                    }
                    val name = binary.compilation.compileDependencyConfigurationName
                    // Built after the build script declared the dependencies, once per compilation.
                    val klibs =
                        project.provider {
                            noOpKlibs.getOrPut(name) {
                                project.configurations
                                    .getByName(name)
                                    .copyRecursive()
                                    .also(sdk::swapForNoOps)
                            }
                        }
                    val check = registerCheck(project, binary.linkTaskName, klibs.flatMap { it.incoming.resolutionResult.rootComponent })
                    binary.linkTaskProvider.configure { link ->
                        // Kotlin's own libraries but the compilation's klibs: the stdlib and platform klibs stay.
                        val kotlinOwn = Callable { binary.compilation.compileDependencyFiles - project.configurations.getByName(name) }
                        link.libraries.setFrom(project.files(kotlinOwn), klibs)
                        link.dependsOn(check)
                    }
                }
            }
        }
    }

    private fun registerCheck(
        project: Project,
        name: String,
        graph: Provider<ResolvedComponentResult>,
    ) = project.tasks.register("appMonitorCheck${name.replaceFirstChar(Char::uppercase)}", NoSdkCheck::class.java) { task ->
        task.configurationName.set(name)
        task.found.set(graph.map(::realSdkIn))
    }
}

/** Fails a release build that still resolves a real SDK module, whoever declared it (ADR-0157). */
@DisableCachingByDefault(because = "Reads the resolved graph and writes nothing")
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

/** Fails a debug build whose `appmonitor-okhttp` is not the plugin's version, which the OkHttp rewrite calls (ADR-0169). */
@DisableCachingByDefault(because = "Reads the resolved graph and writes nothing")
internal abstract class OkHttpVersionCheck : DefaultTask() {
    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val found: Property<String>

    @TaskAction
    fun check() {
        val found = found.get()
        if (found.isNotEmpty() && found != SDK_VERSION) {
            throw GradleException(
                "AppMonitor: ${variantName.get()} resolves appmonitor-okhttp $found, the plugin rewrites OkHttp for $SDK_VERSION; " +
                    "use the same version for both, or set appMonitor { injectOkHttp = false } (ADR-0169)",
            )
        }
    }
}
