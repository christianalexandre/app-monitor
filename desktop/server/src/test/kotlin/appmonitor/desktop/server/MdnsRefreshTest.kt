package appmonitor.desktop.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MdnsRefreshTest {
    private val linkLocal = InetAddress.getByName("169.254.200.69")
    private val lan = InetAddress.getByName("10.0.0.124")

    @Test
    fun `an address that appears is registered and one that went away is closed`() {
        var current = listOf(linkLocal)
        val created = CopyOnWriteArrayList<Registration>()
        val advertiser =
            MdnsAdvertiser.start("test", 1, currentAddresses = { current }) { address ->
                Registration(address).also { created += it }
            }
        assertEquals(listOf(linkLocal), advertiser.addresses)

        current = listOf(lan)
        advertiser.refresh()

        assertEquals(listOf(lan), advertiser.addresses)
        assertEquals(listOf(linkLocal, lan), created.map { it.address })
        awaitTrue { created.first().closed }
        assertTrue(!created.last().closed)
        advertiser.refresh()
        assertEquals(2, created.size, "an address already registered is not registered again")
    }

    @Test
    fun `a failed address is reported and not tried again until it goes away and comes back`() {
        var current = listOf(lan)
        var attempts = 0
        val advertiser =
            MdnsAdvertiser.start("test", 1, currentAddresses = { current }) {
                attempts++
                throw IOException("no")
            }
        advertiser.refresh()
        assertEquals(1, attempts)
        assertTrue(advertiser.problem!!.startsWith("mDNS failed on 10.0.0.124"))

        current = emptyList()
        advertiser.refresh()
        assertEquals("no LAN address to advertise on, devices need this machine's address set", advertiser.problem)
        current = listOf(lan)
        advertiser.refresh()
        assertEquals(2, attempts)
    }

    @Test
    fun `nothing is registered once closed`() {
        var current = listOf(linkLocal)
        val created = CopyOnWriteArrayList<Registration>()
        val advertiser = MdnsAdvertiser.start("test", 1, currentAddresses = { current }) { Registration(it).also { r -> created += r } }

        advertiser.close()
        current = listOf(lan)
        advertiser.refresh()

        assertEquals(1, created.size)
        assertTrue(created.single().closed)
    }

    @Test
    fun `a running server follows the machine's addresses and stops following once stopped`() =
        runBlocking {
            var current = listOf(linkLocal)
            val created = CopyOnWriteArrayList<Registration>()
            val server =
                AppMonitorServer(
                    ServerConfig(host = "127.0.0.1", port = 0),
                    startAdvertiser = { name, port ->
                        MdnsAdvertiser.start(name, port, currentAddresses = { current }) { Registration(it).also { r -> created += r } }
                    },
                    addressCheckPeriod = 20.milliseconds,
                )
            server.start()
            assertEquals(listOf(linkLocal), server.advertisedAddresses)

            current = listOf(lan)
            withTimeout(5.seconds) { while (server.advertisedAddresses != listOf(lan)) delay(10) }
            assertNull(server.advertisingProblem)

            server.stop()
            current = listOf(linkLocal, lan, InetAddress.getByName("10.0.0.125"))
            delay(200)
            assertEquals(2, created.size, "no registration after stop")
            assertTrue(created.all { it.closed })
        }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within 5 s" }
            Thread.sleep(10)
        }
    }

    private class Registration(
        val address: InetAddress,
    ) : AutoCloseable {
        @Volatile var closed = false

        override fun close() {
            closed = true
        }
    }
}
