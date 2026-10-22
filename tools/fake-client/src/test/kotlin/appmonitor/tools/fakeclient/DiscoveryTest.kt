package appmonitor.tools.fakeclient

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryTest {
    @Test
    fun `a failing browser creation closes the ones already created`() =
        runBlocking {
            val addresses = (1..3).map { InetAddress.getByAddress(byteArrayOf(10, 0, 0, it.toByte())) as Inet4Address }
            val created = mutableListOf<Inet4Address>()
            val closed = mutableListOf<Inet4Address>()

            assertFailsWith<IOException> {
                discover(timeoutMs = 1_000, addresses = { addresses }) { address, _ ->
                    if (address == addresses[1]) throw IOException("bind failed")
                    created += address
                    AutoCloseable { closed += address }
                }
            }

            assertEquals(listOf(addresses[0]), created)
            assertEquals(created, closed)
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
}
