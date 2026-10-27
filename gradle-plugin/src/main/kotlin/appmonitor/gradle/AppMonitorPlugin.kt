package appmonitor.gradle

import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/** `io.github.christianalexandre.appmonitor`: rewrites OkHttp's `OkHttpClient(builder)` in an app's debuggable variants (ADR-0112). */
public class AppMonitorPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("appMonitor", AppMonitorExtension::class.java)
        extension.injectOkHttp.convention(true)
        project.pluginManager.withPlugin("com.android.application") {
            val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            components.onVariants(components.selector().all()) { variant ->
                if (!variant.debuggable || !extension.injectOkHttp.get()) return@onVariants
                val graph = variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
                variant.instrumentation.transformClassesWith(OkHttpInjection::class.java, InstrumentationScope.ALL) {
                    it.sdkOnClasspath.set(graph.map(::hasSdk))
                }
            }
        }
    }
}
