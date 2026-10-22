package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ServerLifecycleTest {
    @Test
    fun `failing registration close still closes the rest, frees the port and completes sessions`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val registrations = List(3) { index -> Registration(failOnClose = index == 0) }
            val addresses = listOf("10.0.0.1", "10.0.0.2", "10.0.0.3").map { InetAddress.getByName(it) }
            val server =
                AppMonitorServer(ServerConfig(host = "127.0.0.1", port = port)) { name, boundPort ->
                    val next = registrations.iterator()
                    MdnsAdvertiser.start(name, boundPort, addresses) { next.next() }
                }
            server.start()

            val error = assertFailsWith<IOException> { server.stop() }

            assertEquals("close failed", error.message)
            assertTrue(registrations.all { it.closed })
            ServerSocket(port, 0, InetAddress.getByName("127.0.0.1")).close()
            assertEquals(emptyList(), withTimeout(TIMEOUT_MS) { server.sessions.toList() })
        }

    @Test
    fun `cancelling start during advertising closes every registration and frees the port`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val registrations = CopyOnWriteArrayList<Registration>()
            val registering = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val addresses = listOf("10.0.0.1", "10.0.0.2").map { InetAddress.getByName(it) }
            val server =
                AppMonitorServer(ServerConfig(host = "127.0.0.1", port = port)) { name, boundPort ->
                    MdnsAdvertiser
                        .start(name, boundPort, addresses) {
                            Registration(failOnClose = false).also { registrations += it }
                        }.also {
                            registering.complete(Unit)
                            release.await()
                        }
                }
            val starting = launch(Dispatchers.Default) { server.start() }
            withTimeout(TIMEOUT_MS) { registering.await() }

            starting.cancel()
            release.countDown()
            withTimeout(TIMEOUT_MS) { starting.join() }

            assertEquals(2, registrations.size)
            assertTrue(registrations.all { it.closed })
            ServerSocket(port, 0, InetAddress.getByName("127.0.0.1")).close()
            server.stop()
        }

    @Test
    fun `start after stop fails`() =
        runBlocking<Unit> {
            val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
            server.start()
            server.stop()

            assertFailsWith<IllegalStateException> { server.start() }
        }

    @Test
    fun `client that never sends hello is closed after the handshake timeout`() =
        runBlocking {
            val server =
                AppMonitorServer(
                    ServerConfig(host = "127.0.0.1", port = 0, advertise = false),
                    handshakeTimeout = 200.milliseconds,
                ) { _, _ -> error("not advertising") }
            val client = HttpClient(CIO) { install(WebSockets) }
            try {
                val port = server.start()
                var reason: CloseReason? = null
                withTimeout(TIMEOUT_MS) {
                    client.webSocket(host = "127.0.0.1", port = port, path = Protocol.WEBSOCKET_PATH) {
                        reason = closeReason.await()
                    }
                }
                assertEquals(CloseReason.Codes.PROTOCOL_ERROR.code, reason?.code)
            } finally {
                client.close()
                server.stop()
            }
        }

    private class Registration(
        private val failOnClose: Boolean,
    ) : AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
            if (failOnClose) throw IOException("close failed")
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
