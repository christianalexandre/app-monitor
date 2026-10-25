package appmonitor.gradle

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import kotlin.test.Test
import kotlin.test.assertEquals

class BuilderVisitorTest {
    /** Loads OkHttp and the stand-in entry point itself, with the Builder's bytes rewritten when [rewrite] is set. */
    private class Isolated(
        private val rewrite: Boolean,
    ) : ClassLoader(BuilderVisitorTest::class.java.classLoader) {
        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> {
            if (!name.startsWith("okhttp3.") && name != ENTRY_POINT) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                val original = checkNotNull(parent.getResourceAsStream(name.replace('.', '/') + ".class")).readBytes()
                val bytes = if (rewrite && name == BUILDER) rewritten(original) else original
                return defineClass(name, bytes, 0, bytes.size)
            }
        }

        private fun rewritten(original: ByteArray): ByteArray {
            val reader = ClassReader(original)
            val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
            reader.accept(BuilderVisitor(writer), 0)
            return writer.toByteArray()
        }
    }

    private fun installsAfterBuilding(rewrite: Boolean): Int {
        val loader = Isolated(rewrite)
        val builder = loader.loadClass(BUILDER)
        val client = builder.getMethod("build").invoke(builder.getConstructor().newInstance())
        client.javaClass
            .getMethod("newBuilder")
            .invoke(client)
            .let { builder.getMethod("build").invoke(it) }
        return loader.loadClass(ENTRY_POINT).getField("installs").getInt(null)
    }

    @Test
    fun `build calls the entry point with each builder`() {
        assertEquals(2, installsAfterBuilding(rewrite = true))
    }

    @Test
    fun `untouched build calls nothing`() {
        assertEquals(0, installsAfterBuilding(rewrite = false))
    }
}
