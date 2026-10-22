package appmonitor.sdk.core

import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.DeviceSession
import appmonitor.desktop.server.ServerConfig
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import appmonitor.v1.RequestStarted
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MonitorClientTest {
    private val hello =
        Hello(
            protocol_version = Protocol.VERSION,
            platform = Platform.PLATFORM_JVM,
            device_id = "device-1",
            channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK),
        )
    private val config = ClientConfig(backoffInitial = 50.milliseconds, backoffMax = 200.milliseconds)
    private val cleanup = mutableListOf<suspend () -> Unit>()

    @AfterTest
    fun tearDown() =
        runBlocking {
            cleanup.asReversed().forEach { it() }
        }

    @Test
    fun handshakesWithTheDesktop() =
        runBlocking {
            val (server, port) = desktop()
            val client = client(port)
            val session = withTimeout(5.seconds) { server.sessions.first() }
            assertEquals("device-1", session.hello.device_id)
            client.awaitState(ClientState.Connected)
        }

    @Test
    fun delivers1000EventsInOrder() =
        runBlocking {
            val (server, port) = desktop()
            val client = client(port)
            repeat(1_000) { client.send(request("r$it")) }
            val session = withTimeout(5.seconds) { server.sessions.first() }
            assertEquals(List(1_000) { "r$it" }, session.requestIds(1_000))
        }

    @Test
    fun eventsQueuedWhileTheDesktopIsDownArriveAfterItReturns() =
        runBlocking {
            val (first, port) = desktop()
            val client = client(port)
            withTimeout(5.seconds) { first.sessions.first() }
            client.awaitState(ClientState.Connected)
            first.stop()
            client.awaitState(ClientState.BackingOff)
            repeat(10) { client.send(request("q$it")) }
            val (second, _) = desktop(port)
            val session = withTimeout(5.seconds) { second.sessions.first() }
            assertEquals(List(10) { "q$it" }, session.requestIds(10))
        }

    @Test
    fun dropsEnvelopesOnChannelsTheDesktopDidNotAccept() =
        runBlocking {
            val (server, port) = desktop()
            val client = client(port)
            val session = withTimeout(5.seconds) { server.sessions.first() }
            client.awaitState(ClientState.Connected)
            client.send(Envelope(channel = "logs", type = "Line"))
            client.send(request("kept"))
            // The desktop closes 1002 on a non-accepted channel, so "kept" arriving proves the drop.
            assertEquals(listOf("kept"), session.requestIds(1))
        }

    @Test
    fun aRejectedDeviceWaitsTheLongestDelay() =
        runBlocking {
            val attempts = AtomicInteger()
            val port =
                stubDesktop {
                    attempts.incrementAndGet()
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Rejected"))
                }
            client(port, config.copy(backoffInitial = 10.milliseconds, backoffMax = 2.seconds))
            withTimeout(5.seconds) { while (attempts.get() == 0) delay(10) }
            delay(500)
            assertEquals(1, attempts.get())
        }

    @Test
    fun blockedCleartextStopsTheClientWithOneLogLine() =
        runBlocking {
            val (server, port) = desktop()
            val lines = mutableListOf<String>()
            val client = client(port, cleartextPermitted = { false }, log = { lines += it })
            client.awaitState(ClientState.Stopped)
            assertEquals(1, lines.size)
            assertTrue("debug-overrides" in lines.single())
            server.stop()
            assertEquals(emptyList(), server.sessions.toList())
        }

    @Test
    fun stopWhileDiscoveringReleasesEverything() =
        runBlocking {
            val client = client(discover = { awaitCancellation() })
            client.awaitState(ClientState.Discovering)
            client.stopAndJoin()
        }

    @Test
    fun stopWhileConnectingClosesTheHalfOpenSocket() =
        runBlocking {
            ServerSocket(0).use { listener ->
                val client = client(listener.localPort)
                client.awaitState(ClientState.Connecting)
                val accepted = withContext(Dispatchers.IO) { listener.accept() }
                accepted.use { socket ->
                    client.stopAndJoin()
                    socket.soTimeout = 2_000
                    val input = socket.getInputStream()
                    val end =
                        withContext(Dispatchers.IO) {
                            try {
                                while (input.read() != -1) Unit
                                true
                            } catch (_: SocketTimeoutException) {
                                false
                            }
                        }
                    assertTrue(end, "the client left the socket open")
                }
            }
        }

    @Test
    fun stopWhileHandshakingClosesTheSessionNormally() =
        runBlocking {
            val helloSeen = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<CloseReason?>()
            val port =
                stubDesktop {
                    incoming.receive()
                    helloSeen.complete(Unit)
                    closed.complete(closeReason.await())
                }
            val client = client(port)
            withTimeout(5.seconds) { helloSeen.await() }
            assertEquals(ClientState.Handshaking, client.state.value)
            client.stopAndJoin()
            assertEquals(CloseReason.Codes.NORMAL.code, withTimeout(2.seconds) { closed.await() }?.code)
        }

    @Test
    fun stopWhileConnectedEndsTheDesktopSession() =
        runBlocking {
            val (server, port) = desktop()
            val client = client(port)
            val session = withTimeout(5.seconds) { server.sessions.first() }
            client.awaitState(ClientState.Connected)
            client.stopAndJoin()
            withTimeout(2.seconds) { session.envelopes.toList() }
        }

    @Test
    fun stopWhileBackingOffCancelsTheTimer() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val client = client(port, config.copy(backoffInitial = 60.seconds, backoffMax = 60.seconds))
            client.awaitState(ClientState.BackingOff)
            client.stopAndJoin()
        }

    @Test
    fun anEnvelopeLargerThanAFrameIsDroppedBeforeTheBuffer() =
        runBlocking {
            val client = client(discover = { awaitCancellation() })
            val body = okio.ByteString.of(*ByteArray(Protocol.MAX_FRAME_BYTES.toInt()))
            client.send(Envelope(channel = Protocol.CHANNEL_NETWORK, type = "RequestStarted", payload = body))
            assertEquals(1, client.dropped)
        }

    @Test
    fun eventsAfterStopAreDropped() =
        runBlocking {
            val client = client(discover = { awaitCancellation() })
            client.stopAndJoin()
            client.send(request("late"))
            assertEquals(ClientState.Stopped, client.state.value)
        }

    private suspend fun desktop(port: Int = 0): Pair<AppMonitorServer, Int> {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = port, advertise = false))
        val bound = server.start()
        cleanup += { server.stop() }
        return server to bound
    }

    /** A desktop that runs [handler] after the upgrade instead of the real handshake. */
    private fun stubDesktop(handler: suspend DefaultWebSocketServerSession.() -> Unit): Int {
        val engine: EmbeddedServer<*, *> =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                install(WebSockets)
                routing { webSocket(Protocol.WEBSOCKET_PATH) { handler() } }
            }.start(wait = false)
        cleanup += { engine.stop(0, 1_000) }
        return runBlocking {
            engine.engine
                .resolvedConnectors()
                .first()
                .port
        }
    }

    private fun client(
        port: Int = 0,
        config: ClientConfig = this.config,
        discover: suspend () -> Endpoint? = { Endpoint("127.0.0.1", port) },
        cleartextPermitted: (String) -> Boolean = { true },
        log: (String) -> Unit = {},
    ): MonitorClient {
        val client = MonitorClient(hello, discover, config, cleartextPermitted, log)
        cleanup += { client.stopAndJoin() }
        client.start()
        return client
    }

    private suspend fun MonitorClient.awaitState(expected: ClientState) {
        withTimeout(5.seconds) { state.first { it == expected } }
    }

    /** Stops and checks the release finished within the bound of SDD 04. */
    private suspend fun MonitorClient.stopAndJoin() {
        stop()
        withTimeout(2.seconds) { join() }
        assertEquals(ClientState.Stopped, state.value)
    }

    private fun request(id: String) =
        Envelope(
            channel = Protocol.CHANNEL_NETWORK,
            type = "RequestStarted",
            payload = RequestStarted(request_id = id, method = "GET", url = "https://example.com/$id").encodeByteString(),
        )

    private suspend fun DeviceSession.requestIds(count: Int): List<String> =
        withTimeout(10.seconds) {
            envelopes.take(count).map { RequestStarted.ADAPTER.decode(it.payload).request_id }.toList()
        }
}
