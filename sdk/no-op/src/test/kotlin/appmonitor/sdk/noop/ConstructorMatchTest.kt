package appmonitor.sdk.noop

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipFile
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.Metadata
import kotlin.metadata.jvm.signature
import kotlin.metadata.visibility
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-0169: the constructors an app can call, read from each module's JVM classes, which the ABI dumps can miss. */
class ConstructorMatchTest {
    private val pairs = listOf("core-no-op" to "core", "okhttp-no-op" to "android-okhttp", "ktor-no-op" to "ktor")

    @Test
    fun `every no-op class has its real class's constructors`() {
        var compared = 0
        for ((noOp, real) in pairs) {
            // File facades (`…Kt`) are in the dump without class metadata; they have no constructor.
            val api = classesIn(sdkDir.resolve("$real/api/jvm/$real.api").readText())
            val expected = constructorsIn(jarOf(real)).filterKeys { it in api }
            assertEquals(expected, constructorsIn(jarOf(noOp)).filterKeys { it in api }, "constructors differ: sdk/$real, sdk/$noOp")
            compared += expected.values.sumOf { it.size }
        }
        assertTrue(compared > 0, "no constructor compared")
    }

    @Test
    fun `a public constructor beside an internal one is read`() {
        val interceptor = constructorsIn(jarOf("android-okhttp"))["appmonitor/sdk/okhttp/AppMonitorInterceptor"]
        assertContains(interceptor.orEmpty(), "<init>()V")
        assertTrue(interceptor.orEmpty().none { "MonitorSink" in it }, "an internal constructor is not API: $interceptor")
    }

    private val sdkDir = File(System.getProperty("appmonitor.sdkDir"))

    private fun jarOf(module: String) = File(checkNotNull(System.getProperty("appmonitor.jar.$module")) { "no jar for sdk/$module" })
}

/** The classes a JVM dump declares, by internal name. */
internal fun classesIn(dump: String): Set<String> =
    dump
        .lines()
        .filter { it.endsWith("{") }
        .mapNotNull { Regex("""class (\S+)""").find(it)?.groupValues?.get(1) }
        .toSet()

/** Each public class in [jar] to its public and protected constructors' JVM signatures. */
internal fun constructorsIn(jar: File): Map<String, Set<String>> =
    ZipFile(jar).use { zip ->
        zip
            .entries()
            .asSequence()
            .filter { it.name.endsWith(".class") }
            .mapNotNull { entry -> kotlinClass(zip.getInputStream(entry).readBytes()) }
            .filter { it.visibility == Visibility.PUBLIC }
            .associate { kmClass ->
                kmClass.name.replace('.', '$') to
                    kmClass.constructors
                        .filter { it.visibility == Visibility.PUBLIC || it.visibility == Visibility.PROTECTED }
                        .mapNotNull { it.signature?.toString() }
                        .toSet()
            }
    }

private fun kotlinClass(bytes: ByteArray): kotlin.metadata.KmClass? {
    val fields = mutableMapOf<String, Any>()
    ClassReader(bytes).accept(
        object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor? = if (descriptor == "Lkotlin/Metadata;") MetadataReader(fields) else null
        },
        ClassReader.SKIP_CODE,
    )
    if (fields.isEmpty()) return null
    @Suppress("UNCHECKED_CAST")
    val metadata =
        Metadata(
            kind = fields["k"] as Int?,
            metadataVersion = (fields["mv"] as List<Int>?)?.toIntArray(),
            data1 = (fields["d1"] as List<String>?)?.toTypedArray(),
            data2 = (fields["d2"] as List<String>?)?.toTypedArray(),
            extraString = fields["xs"] as String?,
            packageName = fields["pn"] as String?,
            extraInt = fields["xi"] as Int?,
        )
    return (KotlinClassMetadata.readLenient(metadata) as? KotlinClassMetadata.Class)?.kmClass
}

/** The values of `@kotlin.Metadata`, its arrays as lists. */
private class MetadataReader(
    private val fields: MutableMap<String, Any>,
) : AnnotationVisitor(Opcodes.ASM9) {
    override fun visit(
        name: String,
        value: Any,
    ) {
        fields[name] = if (value is IntArray) value.toList() else value
    }

    override fun visitArray(name: String): AnnotationVisitor {
        val values = mutableListOf<Any>()
        fields[name] = values
        return object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(
                ignored: String?,
                value: Any,
            ) {
                values += value
            }
        }
    }
}
