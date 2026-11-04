package appmonitor.desktop.proxy

import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProxyPassThroughTest {
    private val originCaDir = createTempDirectory("origin-ca")
    private val originCa = ProxyCa.loadOrCreate(originCaDir)
    private val originCaFile = originCaDir.resolve(ProxyCa.CERTIFICATE_FILE).toString()

    @Test
    fun `a listed host is tunnelled untouched, with its own certificate, and nothing is stored`() {
        Origin(originCa.serverContext("127.0.0.1", alpn = false)).use { origin ->
            Origin(null).use { plain ->
                withProxy(passThrough = { listOf("127.0.0.1") }) {
                    // Only the origin's own CA is trusted: an intercepted tunnel would show the proxy's leaf and fail.
                    val result =
                        curl("--proxy", url, "--cacert", originCaFile, "--data", "ping", "https://127.0.0.1:${origin.port}/through")

                    assertEquals(0, result.exit, result.output)
                    assertEquals("POST /through ping", result.output)
                    val marker = "http://127.0.0.1:${plain.port}/marker"
                    assertEquals(0, curl("--proxy", url, marker).exit)
                    assertEquals(listOf(marker), ended(1).map { it.exchange.url }, stored())
                }
            }
        }
    }

    @Test
    fun `the list is read at each CONNECT`() {
        Origin(originCa.serverContext("127.0.0.1", alpn = false)).use { origin ->
            val hosts = mutableListOf<String>()
            withProxy(upstreamTrust = trusting(originCa), passThrough = { hosts.toList() }) {
                val target = "https://127.0.0.1:${origin.port}/again"

                assertNotEquals(0, curl("--proxy", url, "--cacert", originCaFile, target).exit)
                hosts += "127.0.0.1"
                val through = curl("--proxy", url, "--cacert", originCaFile, target)

                assertEquals(0, through.exit, through.output)
                val exchange = ended(1).single().exchange
                assertEquals("CONNECT", exchange.method, stored())
                assertTrue(exchange.failed)
            }
        }
    }

    @Test
    fun `a listed host that refuses the connection is a 502 to the client`() {
        val closed = ServerSocket(0).use { it.localPort }
        withProxy(passThrough = { listOf("127.0.0.1") }) {
            val result = curl("--proxy", url, "--cacert", originCaFile, "https://127.0.0.1:$closed/")

            assertNotEquals(0, result.exit)
            assertTrue(result.output.contains("502"), result.output)
            assertEquals("none", stored())
        }
    }

    @Test
    fun `entries match a host exactly or a wildcard's subdomains`() {
        val entries = listOf("Pinned.Example.com.", "*.bank.test", " ", "[::1]")

        assertTrue(passesThrough("pinned.example.com", entries))
        assertTrue(passesThrough("api.bank.test", entries))
        assertTrue(passesThrough("a.b.bank.test", entries))
        assertTrue(passesThrough("::1", entries))
        assertFalse(passesThrough("bank.test", entries))
        assertFalse(passesThrough("notbank.test", entries))
        assertFalse(passesThrough("other.example.com", entries))
        assertFalse(passesThrough("example.com", entries))
    }
}
