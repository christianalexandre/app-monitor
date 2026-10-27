package appmonitor.desktop.proxy

import java.net.Socket
import java.net.SocketException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** One test per cell of SDD 11's lifecycle table. */
class ProxyLifecycleTest {
    private val ca = ProxyCa.loadOrCreate(createTempDirectory("ca"))

    @Test
    fun `stop before start does nothing`() {
        ProxyServer(ca, {}, port = 0).stop()
    }

    @Test
    fun `start twice throws`() {
        val server = ProxyServer(ca, {}, port = 0)
        server.start()
        try {
            assertFailsWith<IllegalStateException> { server.start() }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `start after stop throws`() {
        val server = ProxyServer(ca, {}, port = 0)
        server.start()
        server.stop()
        assertFailsWith<IllegalStateException> { server.start() }
    }

    @Test
    fun `stop twice is idempotent`() {
        val server = ProxyServer(ca, {}, port = 0)
        server.start()
        server.stop()
        server.stop()
    }

    @Test
    fun `stop closes open client connections`() {
        val server = ProxyServer(ca, {}, port = 0)
        val port = server.start()
        Socket(ProxyServer.HOST, port).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write("GET http://127.0.0.1:1/ HTTP/1.1\r\n".toByteArray())
            server.stop()
            // Unread request bytes make the close a reset; either way the connection is gone.
            val read = runCatching { client.getInputStream().read() }
            assertTrue(read.getOrNull() == -1 || read.exceptionOrNull() is SocketException, read.toString())
        }
    }

    @Test
    fun `stop frees the port`() {
        val server = ProxyServer(ca, {}, port = 0)
        val port = server.start()
        server.stop()
        val next = ProxyServer(ca, {}, port = port)
        assertEquals(port, next.start())
        next.stop()
    }
}
