package appmonitor.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

internal const val CLIENT = "okhttp3.OkHttpClient"
internal const val BUILDER = "okhttp3.OkHttpClient\$Builder"
internal const val ENTRY_POINT = "appmonitor.sdk.okhttp.AppMonitorOkHttp"
private const val NEW_WEB_SOCKET = "(Lokhttp3/Request;Lokhttp3/WebSocketListener;)Lokhttp3/WebSocket;"

// The published module and its Android variant; this repository's project is matched by path.
private val SDK_MODULES = setOf("appmonitor-okhttp", "appmonitor-okhttp-android")

/**
 * The version of `io.github.christianalexandre:appmonitor-okhttp` in the graph under [root], [SDK_VERSION] for this
 * repository's project, null without it.
 */
internal fun okHttpSdkVersion(root: ResolvedComponentResult): String? =
    components(root).firstNotNullOfOrNull { component ->
        val module = component.moduleVersion
        when {
            (component.id as? ProjectComponentIdentifier)?.projectPath == SdkModule.OKHTTP.path -> SDK_VERSION
            module != null && module.group == GROUP && module.name in SDK_MODULES -> module.version
            else -> null
        }
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
        if (name == "newWebSocket" && descriptor == NEW_WEB_SOCKET) return WebSocketEntry(method)
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

// ADR-0159: `if ((ws = AppMonitorOkHttp.newWebSocket(this, request, listener)) != null) return ws;` first; AGP
// recomputes the frames (COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS), so none is written here.
private class WebSocketEntry(
    next: MethodVisitor,
) : MethodVisitor(Opcodes.ASM9, next) {
    override fun visitCode() {
        super.visitCode()
        val openAsUsual = Label()
        visitVarInsn(Opcodes.ALOAD, 0)
        visitVarInsn(Opcodes.ALOAD, 1)
        visitVarInsn(Opcodes.ALOAD, 2)
        visitMethodInsn(
            Opcodes.INVOKESTATIC,
            ENTRY_POINT.replace('.', '/'),
            "newWebSocket",
            "(L${CLIENT.replace('.', '/')};Lokhttp3/Request;Lokhttp3/WebSocketListener;)Lokhttp3/WebSocket;",
            false,
        )
        visitInsn(Opcodes.DUP)
        visitJumpInsn(Opcodes.IFNULL, openAsUsual)
        visitInsn(Opcodes.ARETURN)
        visitLabel(openAsUsual)
        visitInsn(Opcodes.POP)
    }
}
