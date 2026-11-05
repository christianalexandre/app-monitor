package appmonitor.gradle

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientVisitorTest {
    /** Loads OkHttp and the stand-in entry point itself, with the client's bytes rewritten when [rewrite] is set. */
    private class Isolated(
        private val rewrite: Boolean,
    ) : ClassLoader(ClientVisitorTest::class.java.classLoader) {
        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> {
            if (!name.startsWith("okhttp3.") && name != ENTRY_POINT) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                val original = checkNotNull(parent.getResourceAsStream(name.replace('.', '/') + ".class")).readBytes()
                val bytes = if (rewrite && name == CLIENT) rewritten(original) else original
                return defineClass(name, bytes, 0, bytes.size)
            }
        }

        private fun rewritten(original: ByteArray): ByteArray {
            val reader = ClassReader(original)
            // As AGP's COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS: the newWebSocket entry adds a branch.
            val writer = ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
            reader.accept(ClientVisitor(writer), 0)
            return writer.toByteArray()
        }
    }

    // A client from Builder.build(), one from its newBuilder(), and one from OkHttpClient(), which skips build().
    private fun installsAfterBuilding(rewrite: Boolean): Int {
        val loader = Isolated(rewrite)
        val builder = loader.loadClass(BUILDER)
        val client = builder.getMethod("build").invoke(builder.getConstructor().newInstance())
        client.javaClass
            .getMethod("newBuilder")
            .invoke(client)
            .let { builder.getMethod("build").invoke(it) }
        loader.loadClass(CLIENT).getConstructor().newInstance()
        return loader.loadClass(ENTRY_POINT).getField("installs").getInt(null)
    }

    @Test
    fun `every client calls the entry point with its builder`() {
        assertEquals(3, installsAfterBuilding(rewrite = true))
    }

    @Test
    fun `untouched clients call nothing`() {
        assertEquals(0, installsAfterBuilding(rewrite = false))
    }

    /** Opens a socket to a closed port through a client loaded by [loader]; returns it and the entry's call count. */
    private fun openWebSocket(
        loader: ClassLoader,
        wrap: Boolean,
    ): Pair<Any, Int> {
        val builder = loader.loadClass("okhttp3.Request\$Builder")
        var request = builder.getConstructor().newInstance()
        request = builder.getMethod("url", String::class.java).invoke(request, "http://127.0.0.1:9/ws")
        if (wrap) request = builder.getMethod("header", String::class.java, String::class.java).invoke(request, "x-wrap", "1")
        request = builder.getMethod("build").invoke(request)
        val client = loader.loadClass(CLIENT).getConstructor().newInstance()
        val requestType = loader.loadClass("okhttp3.Request")
        val listenerType = loader.loadClass("okhttp3.WebSocketListener")
        val socket =
            client.javaClass
                .getMethod("newWebSocket", requestType, listenerType)
                .invoke(client, request, loader.loadClass("okhttp3.StandInListener").getConstructor().newInstance())
        socket.javaClass.getMethod("cancel").invoke(socket)
        return socket to loader.loadClass(ENTRY_POINT).getField("webSockets").getInt(null)
    }

    private fun Any.header(name: String): String? {
        val request = javaClass.getMethod("request").invoke(this)
        return request.javaClass.getMethod("header", String::class.java).invoke(request, name) as String?
    }

    @Test
    fun `newWebSocket asks the entry point first and opens the socket as usual on null`() {
        val (socket, calls) = openWebSocket(Isolated(rewrite = true), wrap = false)

        assertEquals(1, calls)
        assertEquals(null, socket.header("x-inner"))
    }

    @Test
    fun `newWebSocket returns the entry point's socket`() {
        val (socket, calls) = openWebSocket(Isolated(rewrite = true), wrap = true)

        // The entry's own call into the client asks again and gets null.
        assertEquals(2, calls)
        assertEquals("1", socket.header("x-inner"))
    }

    @Test
    fun `an untouched newWebSocket calls nothing`() {
        assertEquals(0, openWebSocket(Isolated(rewrite = false), wrap = false).second)
    }
}
