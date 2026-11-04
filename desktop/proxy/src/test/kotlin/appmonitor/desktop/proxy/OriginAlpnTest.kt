package appmonitor.desktop.proxy

import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginAlpnTest {
    private val originCa = ProxyCa.loadOrCreate(createTempDirectory("origin-ca"))
    private val group = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val alpn = OriginAlpn(context(originCa), timeoutMs = 300)

    @AfterTest
    fun close() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync()
    }

    @Test
    fun `an origin offering http2 says so, one without says no`() {
        Http2Origin(originCa.serverContext("127.0.0.1")).use { assertTrue(ask(it.port)) }
        Origin(originCa.serverContext("127.0.0.1", alpn = false)).use { assertFalse(ask(it.port)) }
    }

    @Test
    fun `a refused port counts as http2 and is not kept`() {
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertTrue(ask(closed))
        Origin(originCa.serverContext("127.0.0.1", alpn = false), closed).use { assertFalse(ask(closed)) }
    }

    @Test
    fun `an origin refusing the handshake counts as http2 and is not kept`() {
        val other = ProxyCa.loadOrCreate(createTempDirectory("other-ca"))
        Origin(other.serverContext("127.0.0.1", alpn = false)).use { origin ->
            assertTrue(ask(origin.port))
            assertEquals(1, origin.connections)
            assertTrue(ask(origin.port))
            assertEquals(2, origin.connections)
        }
    }

    @Test
    fun `a silent origin counts as http2 within the bound`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { silent ->
            val before = System.nanoTime()
            assertTrue(ask(silent.localPort))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 2_000)
        }
    }

    private fun ask(port: Int): Boolean {
        val answer = CompletableFuture<Boolean>()
        val loop = group.next()
        loop.execute { alpn.ask("127.0.0.1", port, loop, answer::complete) }
        return answer.get(5, TimeUnit.SECONDS)
    }

    private fun context(ca: ProxyCa): SslContext =
        SslContextBuilder
            .forClient()
            .trustManager(trusting(ca))
            .endpointIdentificationAlgorithm("HTTPS")
            .applicationProtocolConfig(
                ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    ApplicationProtocolNames.HTTP_2,
                    ApplicationProtocolNames.HTTP_1_1,
                ),
            ).build()
}
