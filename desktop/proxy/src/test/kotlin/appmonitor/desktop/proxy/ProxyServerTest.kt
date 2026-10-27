package appmonitor.desktop.proxy

import appmonitor.v1.CaptureLayer
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ProxyServerTest {
    private val originCaDir = createTempDirectory("origin-ca")
    private val originCa = ProxyCa.loadOrCreate(originCaDir)

    @Test
    fun `curl trusting the proxy ca gets its https response and the exchange is stored`() {
        Origin(originCa.serverContext("127.0.0.1", alpn = false)).use { origin ->
            withProxy(upstreamTrust = trusting(originCa)) {
                val target = "https://127.0.0.1:${origin.port}/hello?x=1"
                val result = curl("--proxy", url, "--http1.1", "--cacert", caFile.toString(), "--data", "ping", target)

                assertEquals(0, result.exit, result.output)
                assertEquals("POST /hello?x=1 ping", result.output)
                val detail = ended(1).single()
                assertEquals(CaptureLayer.CAPTURE_LAYER_PROXY, detail.exchange.captureLayer)
                assertEquals("POST", detail.exchange.method)
                assertEquals(target, detail.exchange.url)
                assertEquals(200, detail.exchange.statusCode)
                assertEquals("ping", detail.request!!.body.utf8())
                assertEquals("POST /hello?x=1 ping", detail.response!!.body.utf8())
                assertEquals(
                    ProxyRecording.HELLO.device_name,
                    storage
                        .sessions(storage.runId)
                        .single()
                        .hello.device_name,
                )
            }
        }
    }

    @Test
    fun `plain http is relayed and stored`() {
        Origin(null).use { origin ->
            withProxy {
                val target = "http://127.0.0.1:${origin.port}/plain"
                val result = curl("--proxy", url, target)

                assertEquals(0, result.exit, result.output)
                assertEquals("GET /plain ", result.output)
                val detail = ended(1).single()
                assertEquals(target, detail.exchange.url)
                assertEquals(200, detail.exchange.statusCode)
            }
        }
    }

    @Test
    fun `keep alive requests on one connection are each stored`() {
        Origin(originCa.serverContext("127.0.0.1", alpn = false)).use { origin ->
            withProxy(upstreamTrust = trusting(originCa)) {
                val base = "https://127.0.0.1:${origin.port}"
                val result = curl("--proxy", url, "--http1.1", "--cacert", caFile.toString(), "$base/one", "$base/two")

                assertEquals(0, result.exit, result.output)
                assertEquals(listOf("$base/one", "$base/two"), ended(2).map { it.exchange.url })
            }
        }
    }

    @Test
    fun `a client refusing the proxy certificate is a failed exchange, not a hang`() {
        Origin(originCa.serverContext("127.0.0.1", alpn = false)).use { origin ->
            withProxy(upstreamTrust = trusting(originCa)) {
                // A CA that is not the proxy's: curl reaches the handshake and refuses the leaf, with or without a system bundle.
                val otherCa = originCaDir.resolve(ProxyCa.CERTIFICATE_FILE).toString()
                val result = curl("--proxy", url, "--cacert", otherCa, "https://127.0.0.1:${origin.port}/pinned")

                assertNotEquals(0, result.exit)
                val exchange =
                    runCatching {
                        ended(1)
                    }.getOrElse { fail("no CONNECT ended; stored: ${stored()}; curl: ${result.output}", it) }.single().exchange
                assertEquals("CONNECT", exchange.method)
                assertTrue(exchange.failed)
                assertTrue(exchange.error!!.startsWith("TLS handshake with the client failed"), exchange.error)
            }
        }
    }

    @Test
    fun `a client closing its tunnel before any TLS byte ends the CONNECT exchange`() {
        Origin(originCa.serverContext("127.0.0.1", alpn = false)).use { origin ->
            withProxy(upstreamTrust = trusting(originCa)) {
                Socket("127.0.0.1", port).use { socket ->
                    socket.getOutputStream().write("CONNECT 127.0.0.1:${origin.port} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                    val reply = socket.getInputStream().bufferedReader().readLine()
                    assertTrue(reply.startsWith("HTTP/1.1 200"), reply)
                }

                val exchange = ended(1).single().exchange
                assertEquals("CONNECT", exchange.method)
                assertTrue(exchange.failed)
                assertTrue(exchange.error!!.startsWith("TLS handshake with the client failed"), exchange.error)
            }
        }
    }

    @Test
    fun `a client resetting its connection mid handshake ends the CONNECT exchange`() {
        Origin(originCa.serverContext("127.0.0.1", alpn = false)).use { origin ->
            withProxy(upstreamTrust = trusting(originCa)) {
                Socket("127.0.0.1", port).use { socket ->
                    socket.getOutputStream().write("CONNECT 127.0.0.1:${origin.port} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                    val reply = socket.getInputStream().bufferedReader().readLine()
                    assertTrue(reply.startsWith("HTTP/1.1 200"), reply)
                    // The start of a ClientHello turns the tunnel to TLS; a zero linger closes with a reset, no alert.
                    socket.getOutputStream().write(byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x40, 0x01, 0x00, 0x00, 0x3c, 0x03, 0x03))
                    socket.getOutputStream().flush()
                    Thread.sleep(200)
                    socket.setSoLinger(true, 0)
                }

                val exchange = ended(1).single().exchange
                assertEquals("CONNECT", exchange.method)
                assertTrue(exchange.failed)
                assertTrue(exchange.error!!.startsWith("TLS handshake with the client failed"), exchange.error)
            }
        }
    }

    @Test
    fun `an origin certificate the proxy refuses is a failed exchange and a 502`() {
        Origin(originCa.serverContext("127.0.0.1", alpn = false)).use { origin ->
            withProxy {
                val result =
                    curl(
                        "--proxy",
                        url,
                        "--http1.1",
                        "--cacert",
                        caFile.toString(),
                        "-w",
                        "%{http_code}",
                        "https://127.0.0.1:${origin.port}/refused",
                    )

                assertTrue(result.output.endsWith("502"), result.output)
                val exchange = ended(1).single().exchange
                assertTrue(exchange.failed)
                assertTrue(exchange.error!!.startsWith("TLS handshake with 127.0.0.1:${origin.port} failed"), exchange.error)
            }
        }
    }

    @Test
    fun `a refused origin port is a failed exchange and a 502`() {
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        withProxy {
            val result = curl("--proxy", url, "-w", "%{http_code}", "http://127.0.0.1:$closed/none")

            assertTrue(result.output.endsWith("502"), result.output)
            val exchange = ended(1).single().exchange
            assertTrue(exchange.error!!.startsWith("Could not connect to 127.0.0.1:$closed"), exchange.error)
        }
    }

    @Test
    fun `a request that is not for a proxy gets a 400 and nothing is stored`() {
        withProxy {
            val result = curl("-w", "%{http_code}", "$url/direct")

            assertTrue(result.output.endsWith("400"), result.output)
            storage.flush()
            assertTrue(storage.sessions(storage.runId).flatMap { storage.exchangesOfSession(it.id) }.isEmpty())
        }
    }

    @Test
    fun `a taken port fails start with a bind exception`() {
        ServerSocket(0, 1, InetAddress.getByName(ProxyServer.HOST)).use { taken ->
            val server = ProxyServer(originCa, {}, port = taken.localPort)
            assertFailsWith<BindException> { server.start() }
            server.stop()
        }
    }

    @Test
    fun `it listens on loopback only`() {
        val lan =
            NetworkInterface
                .networkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses().toList() }
                .firstOrNull { it is Inet4Address }
        val server = ProxyServer(originCa, {}, port = 0)
        val port = server.start()
        try {
            Socket(ProxyServer.HOST, port).close()
            // Refused, or unreachable where the runner denies local network access (DEBT-0046).
            if (lan != null) assertFailsWith<SocketException> { Socket(lan, port).close() }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `the ca is kept across loads and owner only`() {
        val dir = createTempDirectory("ca")
        val first = ProxyCa.loadOrCreate(dir)
        val second = ProxyCa.loadOrCreate(dir)

        assertEquals(first.certificate, second.certificate)
        if ("posix" in dir.fileSystem.supportedFileAttributeViews()) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(dir.resolve(ProxyCa.KEY_FILE)))
        }
        ProxyCa.delete(dir)
        assertNotEquals(first.certificate, ProxyCa.loadOrCreate(dir).certificate)
    }

    @Test
    fun `a ca about to expire is replaced`() {
        val dir = createTempDirectory("ca")
        val first = ProxyCa.loadOrCreate(dir)
        val later = first.certificate.notAfter.toInstant()

        assertNotEquals(first.certificate, ProxyCa.loadOrCreate(dir) { later }.certificate)
    }
}
