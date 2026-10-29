package appmonitor.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

internal const val CLIENT = "okhttp3.OkHttpClient"
internal const val BUILDER = "okhttp3.OkHttpClient\$Builder"
internal const val ENTRY_POINT = "appmonitor.sdk.okhttp.AppMonitorOkHttp"

// The published module and its Android variant; this repository's project is matched by path.
private val SDK_MODULES = setOf("appmonitor-okhttp", "appmonitor-okhttp-android")

/** Whether the graph under [root] holds `io.github.christianalexandre:appmonitor-okhttp`. */
internal fun hasSdk(root: ResolvedComponentResult): Boolean {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (!seen.add(component)) continue
        val module = component.moduleVersion
        if (module != null && module.group == GROUP && module.name in SDK_MODULES) return true
        if ((component.id as? ProjectComponentIdentifier)?.projectPath == SdkModule.OKHTTP.path) return true
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
    }
    return false
}

/** Calls `AppMonitorOkHttp.install(builder)` on entry of `OkHttpClient(builder)`, when the SDK is on the classpath. */
internal abstract class OkHttpInjection : AsmClassVisitorFactory<OkHttpInjection.Parameters> {
    interface Parameters : InstrumentationParameters {
        // An input, not ClassContext.loadClassData: AGP shares a dependency's transformed classes across modules,
        // and from the dependency graph, not its files: a transform's inputs are read before any task runs.
        @get:Input
        val sdkOnClasspath: Property<Boolean>
    }

    override fun isInstrumentable(classData: ClassData): Boolean = classData.className == CLIENT

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor {
        if (!parameters.get().sdkOnClasspath.get()) {
            Logging.getLogger(OkHttpInjection::class.java).lifecycle(
                "AppMonitor: appmonitor-okhttp is not on this variant's runtime classpath; OkHttp is not rewritten.",
            )
            return nextClassVisitor
        }
        return ClientVisitor(nextClassVisitor)
    }
}

// ADR-0127: the constructor every client goes through, `OkHttpClient()` and `Builder.build()` alike.
internal class ClientVisitor(
    next: ClassVisitor,
) : ClassVisitor(Opcodes.ASM9, next) {
    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val method = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (name != "<init>" || descriptor != "(L${BUILDER.replace('.', '/')};)V") return method
        return object : MethodVisitor(Opcodes.ASM9, method) {
            override fun visitCode() {
                super.visitCode()
                visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    ENTRY_POINT.replace('.', '/'),
                    "install",
                    "(L${BUILDER.replace('.', '/')};)V",
                    false,
                )
            }
        }
    }
}
