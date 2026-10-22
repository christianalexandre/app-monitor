package appmonitor.desktop.server

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MdnsPartialFailureTest {
    @Test
    fun `failed registration closes every instance already created`() {
        val created = mutableListOf<Registration>()
        val addresses = listOf("10.0.0.1", "10.0.0.2", "10.0.0.3").map { InetAddress.getByName(it) }

        val error =
            assertFailsWith<IOException> {
                MdnsAdvertiser.start("test", 1, addresses) { address ->
                    if (address == addresses[1]) throw IOException("register failed on $address")
                    Registration().also { created += it }
                }
            }

        assertEquals("register failed on ${addresses[1]}", error.message)
        assertEquals(1, created.size)
        assertTrue(created.all { it.closed })
    }

    @Test
    fun `failed advertising releases the bound port`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val server =
                AppMonitorServer(ServerConfig(host = "127.0.0.1", port = port)) { _, _ ->
                    throw IOException("mDNS unavailable")
                }

            assertFailsWith<IOException> { server.start() }

            ServerSocket(port, 0, InetAddress.getByName("127.0.0.1")).close()
            assertTrue(server.advertisedAddresses.isEmpty())
        }

    private class Registration : AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
        }
    }
}
