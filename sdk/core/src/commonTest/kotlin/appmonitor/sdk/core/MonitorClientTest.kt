package appmonitor.sdk.core

import appmonitor.protocol.Protocol
import appmonitor.v1.DeliveryReport
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import appmonitor.v1.RequestStarted
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.openReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import okio.ByteString.Companion.toByteString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** SDD 04's states against [StandInDesktop], on every target (ADR-0066 C). */
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
            val desktop = desktop()
            val client = client(desktop.port)
            assertEquals("device-1", withTimeout(5.seconds) { desktop.hellos.receive() }.device_id)
            client.awaitState(ClientState.Connected)
        }

    // ADR-0168: a second desktop found after the first one approved the install is refused, a manual host is not.
    @Test
    fun aSecondDesktopIsRefusedOnceTheFirstIsPinned() =
        runBlocking {
            val first = desktop(desktopId = "first")
            val second = desktop(desktopId = "second")
            val pin = MemoryPin()
            val found = Channel<Endpoint>(Channel.UNLIMITED)
            val logs = Channel<String>(Channel.UNLIMITED)
            found.send(Endpoint(LOOPBACK, first.port))
            val client = client(discover = { found.receive() }, log = { logs.trySend(it) }, pin = pin)
            client.awaitState(ClientState.Connected)
            assertEquals("first", pin.pinned())

            first.stop()
            found.send(Endpoint(LOOPBACK, second.port))
            withTimeout(5.seconds) { second.hellos.receive() }
            assertTrue(withTimeout(5.seconds) { logs.receive() }.contains("not the one this install paired with"))
            client.send(request("kept"))
            client.awaitState(ClientState.BackingOff)
            assertEquals("first", pin.pinned())
            assertTrue(second.envelopes.isEmpty)

            found.send(Endpoint(LOOPBACK, second.port, checksPin = false))
            assertEquals(listOf("kept"), second.requestIds(1))
            client.awaitState(ClientState.Connected)
        }

    @Test
    fun aDesktopWithoutAnIdIsNotPinned() =
        runBlocking {
            val desktop = desktop()
            val pin = MemoryPin()
            val client = client(desktop.port, pin = pin)
            client.awaitState(ClientState.Connected)
            assertNull(pin.pinned())
        }

    @Test
    fun delivers1000EventsInOrder() =
        runBlocking {
            val desktop = desktop()
            val client = client(desktop.port)
            repeat(1_000) { client.send(request("r$it")) }
            assertEquals(List(1_000) { "r$it" }, desktop.requestIds(1_000))
        }

    @Test
    fun eventsQueuedWhileTheDesktopIsDownArriveAfterItReturns() =
        runBlocking {
            val first = desktop()
            val client = client(first.port)
            client.awaitState(ClientState.Connected)
            first.stop()
            client.awaitState(ClientState.BackingOff)
            repeat(10) { client.send(request("q$it")) }
            val second = desktop(first.port)
            assertEquals(List(10) { "q$it" }, second.requestIds(10))
        }

    // ADR-0166: the next session names the last one and the frames written on it, its own report included.
    @Test
    fun aReconnectReportsWhatThePreviousSessionWrote() =
        runBlocking {
            val first = desktop()
            val client = client(first.port)
            repeat(2) { client.send(request("q$it")) }
            assertEquals(listOf("q0", "q1"), first.requestIds(2))
            assertEquals(DeliveryReport(), withTimeout(5.seconds) { first.reports.receive() })
            first.stop()
            client.awaitState(ClientState.BackingOff)
            val second = desktop(first.port)
            assertEquals(
                DeliveryReport(previous_session_id = "stand-in", previous_session_written = 3),
                withTimeout(5.seconds) { second.reports.receive() },
            )
        }

    @Test
    fun aDesktopThatDiesMidSessionEndsInBackingOff() =
        runBlocking {
            val desktop = desktop()
            val client = client(desktop.port)
            client.awaitState(ClientState.Connected)
            val sending = launch { while (true) client.send(request("x")).also { delay(5) } }
            desktop.stop()
            // Writes keep hitting the dead socket: on Darwin a SIGPIPE here kills the test process.
            client.awaitState(ClientState.BackingOff)
            delay(200)
            sending.cancel()
        }

    @Test
    fun aDesktopThatStopsAnsweringPingsEndsInBackingOff() =
        runBlocking {
            val desktop = desktop(silent = true)
            val client = client(desktop.port, config.copy(pingInterval = 300.milliseconds))
            client.awaitState(ClientState.Connected)
            client.awaitState(ClientState.BackingOff)
        }

    @Test
    fun aRejectedDeviceWaitsTheLongestDelay() =
        runBlocking {
            val attempts = Counter()
            val desktop =
                desktop {
                    attempts.increment()
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Rejected"))
                }
            client(desktop.port, config.copy(backoffInitial = 10.milliseconds, backoffMax = 2.seconds))
            withTimeout(5.seconds) { while (attempts.value == 0) delay(10) }
            delay(500)
            assertEquals(1, attempts.value)
        }

    @Test
    fun aRejectionWhoseTeardownThrowsStillWaitsTheLongestDelay() =
        runBlocking {
            val attempts = Counter()
            val desktop =
                desktop {
                    attempts.increment()
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Rejected"))
                }
            client(desktop.port, config.copy(backoffInitial = 10.milliseconds, backoffMax = 2.seconds)) { endpoint, block ->
                webSocket(host = endpoint.host, port = endpoint.port, path = endpoint.path, block = block)
                throw IOException("teardown failed")
            }
            withTimeout(5.seconds) { while (attempts.value == 0) delay(10) }
            delay(500)
            assertEquals(1, attempts.value)
        }

    @Test
    fun anUnreachableDesktopIsLoggedOncePerClient() =
        runBlocking {
            val port = closedPort()
            val attempts = Counter()
            val lines = Lines()
            val discover = {
                attempts.increment()
                Endpoint(LOOPBACK, port)
            }
            client(discover = discover, log = lines::add)
            withTimeout(5.seconds) { while (attempts.value < 3) delay(10) }
            assertEquals(1, lines.all.size)
            assertTrue("$LOOPBACK:$port" in lines.all.single())
            assertTrue(CONNECT_FAILURE_HINT in lines.all.single())
        }

    @Test
    fun anOutageAfterAConnectionIsLoggedAgain() =
        runBlocking {
            val port = closedPort()
            val lines = Lines()
            val client = client(port, log = lines::add)
            withTimeout(5.seconds) { while (lines.all.isEmpty()) delay(10) }
            val desktop = desktop(port)
            client.awaitState(ClientState.Connected)
            desktop.stop()
            withTimeout(5.seconds) { while (lines.all.size < 2) delay(10) }
            assertEquals(2, lines.all.size)
        }

    @Test
    fun manualHostIsTheOnlySource() =
        runBlocking {
            val desktop = desktop()
            val discover =
                discovery(
                    AppMonitorOptions(host = LOOPBACK, port = desktop.port),
                    mdns = { error("mDNS used") },
                    fallbackHost = EMULATOR_HOST,
                )
            val client = client(discover = discover)
            client.awaitState(ClientState.Connected)
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
            val (selector, listener) = rawListener()
            cleanup += {
                listener.close()
                selector.close()
            }
            val port = (listener.localAddress as InetSocketAddress).port
            val client = client(port)
            client.awaitState(ClientState.Connecting)
            val socket = withTimeout(5.seconds) { listener.accept() }
            cleanup += { socket.close() }
            client.stopAndJoin()
            val input = socket.openReadChannel()
            val buffer = ByteArray(1024)
            val ended = withTimeoutOrNull(2.seconds) { while (input.readAvailable(buffer) != -1) Unit }
            assertTrue(ended != null, "the client left the socket open")
        }

    @Test
    fun stopWhileHandshakingClosesTheSessionNormally() =
        runBlocking {
            val helloSeen = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<CloseReason?>()
            val desktop =
                desktop {
                    incoming.receive()
                    helloSeen.complete(Unit)
                    closed.complete((this as DefaultWebSocketSession).closeReason.await())
                }
            val client = client(desktop.port)
            withTimeout(5.seconds) { helloSeen.await() }
            assertEquals(ClientState.Handshaking, client.state.value)
            client.stopAndJoin()
            assertEquals(CloseReason.Codes.NORMAL.code, withTimeout(2.seconds) { closed.await() }?.code)
        }

    @Test
    fun stopWhileBackingOffCancelsTheTimer() =
        runBlocking {
            val client = client(closedPort(), config.copy(backoffInitial = 60.seconds, backoffMax = 60.seconds))
            client.awaitState(ClientState.BackingOff)
            client.stopAndJoin()
        }

    @Test
    fun anEnvelopeLargerThanAFrameIsDroppedBeforeTheBuffer() =
        runBlocking {
            val client = client(discover = { awaitCancellation() })
            val body = ByteArray(Protocol.MAX_FRAME_BYTES.toInt()).toByteString()
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

    private suspend fun desktop(
        port: Int = 0,
        silent: Boolean = false,
        desktopId: String = "",
        handler: (suspend WebSocketSession.() -> Unit)? = null,
    ): StandInDesktop {
        val desktop = StandInDesktop.start(port, silent, desktopId, handler)
        cleanup += { runCatching { desktop.stop() } }
        return desktop
    }

    private fun client(
        port: Int = 0,
        config: ClientConfig = this.config,
        discover: suspend () -> Endpoint? = { Endpoint(LOOPBACK, port) },
        log: (String) -> Unit = {},
        pin: DesktopPin? = null,
        openSession: suspend HttpClient.(Endpoint, suspend DefaultClientWebSocketSession.() -> Unit) -> Unit =
            { endpoint, block -> webSocket(host = endpoint.host, port = endpoint.port, path = endpoint.path, block = block) },
    ): MonitorClient {
        val client = MonitorClient({ hello }, discover, config, log, pin = pin, openSession = openSession)
        cleanup += { client.stopAndJoin() }
        client.start()
        return client
    }

    private fun request(id: String) =
        Envelope(
            channel = Protocol.CHANNEL_NETWORK,
            type = "RequestStarted",
            payload = RequestStarted(request_id = id, method = "GET", url = "https://example.com/$id").encodeByteString(),
        )

    private suspend fun StandInDesktop.requestIds(count: Int): List<String> =
        withTimeout(10.seconds) {
            List(count) { RequestStarted.ADAPTER.decode(envelopes.receive().payload).request_id }
        }
}

private class MemoryPin : DesktopPin {
    private var id: String? = null

    override fun pinned(): String? = id

    override fun pin(desktopId: String): String = id ?: desktopId.also { id = it }
}

internal suspend fun MonitorClient.awaitState(expected: ClientState) {
    withTimeout(5.seconds) { state.first { it == expected } }
}

/** Stops and checks the release finished within the bound of SDD 04. */
internal suspend fun MonitorClient.stopAndJoin() {
    stop()
    withTimeout(2.seconds) { join() }
    assertEquals(ClientState.Stopped, state.value)
}

private class Counter {
    private val lock = SynchronizedObject()
    private var count = 0

    val value: Int get() = synchronized(lock) { count }

    fun increment() {
        synchronized(lock) { count++ }
    }
}

private class Lines {
    private val lock = SynchronizedObject()
    private val lines = mutableListOf<String>()

    val all: List<String> get() = synchronized(lock) { lines.toList() }

    fun add(line: String) {
        synchronized(lock) { lines += line }
    }
}
