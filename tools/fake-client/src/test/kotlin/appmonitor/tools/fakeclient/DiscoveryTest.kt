package appmonitor.tools.fakeclient

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
