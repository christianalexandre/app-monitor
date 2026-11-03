package appmonitor.desktop.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class MdnsPartialFailureTest {
    @Test
    fun `a failed address is skipped, the others advertise and the problem names it`() {
        val addresses = listOf("10.0.0.1", "10.0.0.2", "10.0.0.3").map { InetAddress.getByName(it) }
        val created = mutableListOf<Registration>()

        val advertiser =
            MdnsAdvertiser.start("test", 1, addresses = addresses) { address ->
                if (address == addresses[1]) throw IOException("register failed")
                Registration().also { created += it }
            }

        assertEquals(listOf(addresses[0], addresses[2]), advertiser.addresses)
        assertEquals(2, created.size)
        assertEquals("mDNS failed on 10.0.0.2 (register failed)", advertiser.problem)
    }

    @Test
    fun `every address failing or none at all leaves no registration and says a device needs the host`() {
        val failing =
            MdnsAdvertiser.start("test", 1, addresses = listOf(InetAddress.getByName("10.0.0.1"))) { throw IOException("no") }
        val none = MdnsAdvertiser.start("test", 1, addresses = emptyList()) { error("no address to register") }

        assertTrue(failing.addresses.isEmpty())
        assertEquals("mDNS failed on 10.0.0.1 (no), devices need this machine's address set", failing.problem)
        assertEquals("no LAN address to advertise on, devices need this machine's address set", none.problem)
    }

    @Test
    fun `a server whose every address fails still listens and reports it`() =
        runBlocking {
            val server =
                AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0)) { name, port ->
                    MdnsAdvertiser.start(name, port, addresses = listOf(InetAddress.getByName("10.0.0.1"))) { throw IOException("no") }
                }

            val port = server.start()

            Socket("127.0.0.1", port).close()
            assertTrue(server.advertisingProblem!!.startsWith("mDNS failed on 10.0.0.1"))
            server.stop()
            assertNull(server.advertisingProblem)
        }

    @Test
    fun `each address has its own host name`() {
        assertEquals("appmonitor-192-168-1-5", MdnsAdvertiser.hostName(InetAddress.getByName("192.168.1.5")))
    }

    @Test
    fun `close takes as long as the slowest address, not their sum`() {
        val addresses = listOf("10.0.0.1", "10.0.0.2", "10.0.0.3").map { InetAddress.getByName(it) }
        val entered = CountDownLatch(addresses.size)
        val advertiser =
            MdnsAdvertiser.start("test", 1, addresses = addresses) {
                AutoCloseable {
                    entered.countDown()
                    // Passes only when all three are inside close at once.
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                }
            }

        advertiser.close()
    }

    @Test
    fun `an address that never finishes closing is reported within the bound and the others are closed`() {
        val addresses = listOf("10.0.0.1", "10.0.0.2").map { InetAddress.getByName(it) }
        val quick = Registration()
        val release = CountDownLatch(1)
        val advertiser =
            MdnsAdvertiser.start("test", 1, addresses = addresses, closeTimeout = 200.milliseconds) { address ->
                if (address == addresses[0]) AutoCloseable { release.await() } else quick
            }

        val mark = TimeSource.Monotonic.markNow()
        val error = assertFailsWith<IOException> { advertiser.close() }

        assertTrue(mark.elapsedNow() < 5.seconds)
        assertTrue("10.0.0.1" in error.message.orEmpty(), error.message)
        assertTrue(quick.closed)
        release.countDown()
    }

    @Test
    fun `registration error closes every instance already created`() {
        val created = mutableListOf<Registration>()
        val addresses = listOf("10.0.0.1", "10.0.0.2").map { InetAddress.getByName(it) }

        assertFailsWith<StackOverflowError> {
            MdnsAdvertiser.start("test", 1, addresses = addresses) { address ->
                if (address == addresses[1]) throw StackOverflowError("register failed on $address")
                Registration().also { created += it }
            }
        }

        assertEquals(1, created.size)
        assertTrue(created.all { it.closed })
    }

    @Test
    fun `registration close error still closes the others`() {
        val addresses = listOf("10.0.0.1", "10.0.0.2", "10.0.0.3").map { InetAddress.getByName(it) }
        val registrations = List(3) { index -> Registration(errorOnClose = index == 0) }
        val next = registrations.iterator()
        val advertiser = MdnsAdvertiser.start("test", 1, addresses = addresses) { next.next() }

        assertFailsWith<StackOverflowError> { advertiser.close() }

        assertTrue(registrations.all { it.closed })
    }

    @Test
    fun `failed advertising releases the bound port`() =
        runBlocking {
            val bound = CompletableDeferred<Int>()
            val server =
                AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0)) { _, boundPort ->
                    bound.complete(boundPort)
                    throw IOException("mDNS unavailable")
                }

            assertFailsWith<IOException> { server.start() }

            ServerSocket(bound.await(), 0, InetAddress.getByName("127.0.0.1")).close()
            assertTrue(server.advertisedAddresses.isEmpty())
        }

    private class Registration(
        private val errorOnClose: Boolean = false,
    ) : AutoCloseable {
        @Volatile var closed = false

        override fun close() {
            closed = true
            if (errorOnClose) throw StackOverflowError("close failed")
        }
    }
}
