package appmonitor.tools.fakeclient

import appmonitor.protocol.Protocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import javax.jmdns.impl.ServiceInfoImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryTest {
    @Test
    fun `an address whose browser fails is skipped and the service is found on the next`() =
        runBlocking {
            val addresses = (1..2).map { InetAddress.getByAddress(byteArrayOf(10, 0, 0, it.toByte())) as Inet4Address }
            val closed = CopyOnWriteArrayList<Inet4Address>()

            val target =
                discover(timeoutMs = 5_000, addresses = { addresses }) { address, listener ->
                    if (address == addresses[0]) throw IOException("bind failed")
                    listener.serviceResolved(resolved("desktop", address, 47474))
                    AutoCloseable { closed += address }
                }

            assertEquals(Target("10.0.0.2", 47474, Protocol.WEBSOCKET_PATH), target)
            // Resolved inside browse: that browser is closed on arrival, after discover returns.
            withTimeout(5_000) { while (closed.isEmpty()) delay(10) }
            assertEquals(listOf(addresses[1]), closed.toList())
        }

    @Test
    fun `discover fails only when every address fails`() =
        runBlocking {
            val addresses = (1..3).map { InetAddress.getByAddress(byteArrayOf(10, 0, 0, it.toByte())) as Inet4Address }
            val tried = CopyOnWriteArrayList<Inet4Address>()

            val failure =
                assertFailsWith<IOException> {
                    discover(timeoutMs = 5_000, addresses = { addresses }) { address, _ ->
                        tried += address
                        throw IOException("bind failed on ${address.hostAddress}")
                    }
                }

            assertEquals("bind failed on 10.0.0.1", failure.message)
            assertEquals(addresses, tried.toList())
        }

    @Test
    fun `a browser creation slower than the timeout does not delay discover and is closed`() =
        runBlocking {
            val addresses = (1..2).map { InetAddress.getByAddress(byteArrayOf(10, 0, 0, it.toByte())) as Inet4Address }
            val release = CountDownLatch(1)
            val closed = CopyOnWriteArrayList<Inet4Address>()

            val started = System.nanoTime()
            val target =
                discover(timeoutMs = 200, addresses = { addresses }) { address, _ ->
                    if (address == addresses[1]) release.await()
                    AutoCloseable { closed += address }
                }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertNull(target)
            assertTrue(elapsedMs < 1_000, "discover took $elapsedMs ms")
            assertEquals(listOf(addresses[0]), closed.toList())
            release.countDown()
            withTimeout(5_000) { while (closed.size < 2) delay(10) }
            assertEquals(addresses, closed.toList())
        }

    @Test
    fun `a TXT record that arrives after SRV and A gives the path`() =
        runBlocking {
            val address = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)) as Inet4Address

            val target =
                discover(timeoutMs = 5_000, addresses = { listOf(address) }) { _, listener ->
                    listener.serviceResolved(resolved("AppMonitor", address, 47474))
                    listener.serviceResolved(resolved("AppMonitor", address, 47474, path = "/v2/ws"))
                    AutoCloseable {}
                }

            assertEquals(Target("10.0.0.1", 47474, "/v2/ws"), target)
        }

    @Test
    fun `the service name matches case-insensitively and other names are ignored`() =
        runBlocking {
            val address = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)) as Inet4Address

            val target =
                discover(timeoutMs = 5_000, serviceName = "appmonitor", addresses = { listOf(address) }) { _, listener ->
                    listener.serviceResolved(resolved("Other", address, 1111, path = "/v1/ws"))
                    listener.serviceResolved(resolved("AppMonitor", address, 47474, path = "/v1/ws"))
                    AutoCloseable {}
                }

            assertEquals(Target("10.0.0.1", 47474, "/v1/ws"), target)
        }

    @Test
    fun `a resolve that blocks does not hold back the next service`() =
        runBlocking {
            val address = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)) as Inet4Address
            val stale = CountDownLatch(1)
            var browsed: ServiceListener? = null

            val target =
                discover(
                    timeoutMs = 2_000,
                    addresses = { listOf(address) },
                    resolve = { event ->
                        if (event.name == "Stale") stale.await()
                        checkNotNull(browsed).serviceResolved(resolved(event.name, address, 47474, path = "/v1/ws"))
                    },
                ) { _, listener ->
                    browsed = listener
                    // Both on the browsing thread, as JmDNS delivers them.
                    listener.serviceAdded(resolved("Stale", address, 47474))
                    listener.serviceAdded(resolved("AppMonitor", address, 47474))
                    AutoCloseable {}
                }

            assertEquals(Target("10.0.0.1", 47474, "/v1/ws"), target)
            stale.countDown()
        }

    private fun resolved(
        name: String,
        host: Inet4Address,
        port: Int,
        path: String? = null,
    ): ServiceEvent {
        val properties = path?.let { mapOf(Protocol.MDNS_TXT_PATH to it) } ?: emptyMap()
        val info =
            object : ServiceInfoImpl(Protocol.MDNS_SERVICE_TYPE, name, "", port, 0, 0, false, properties) {
                override fun getInet4Addresses() = arrayOf(host)
            }
        return object : ServiceEvent(info) {
            override fun getDNS() = error("not browsed")

            override fun getType() = Protocol.MDNS_SERVICE_TYPE

            override fun getName() = name

            override fun getInfo() = info
        }
    }
}
