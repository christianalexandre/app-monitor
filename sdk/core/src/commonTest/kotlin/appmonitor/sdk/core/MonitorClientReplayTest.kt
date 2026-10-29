package appmonitor.sdk.core

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import appmonitor.v1.ReplayRefused
import appmonitor.v1.ReplayRequest
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A `ReplayRequest` as the device answers it (ADR-0111 D.1, ADR-0120 A.1, ADR-0122). */
class MonitorClientReplayTest {
    private val hello =
        Hello(
            protocol_version = Protocol.VERSION,
            platform = Platform.PLATFORM_JVM,
            device_id = "device-1",
            channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK, Protocol.CHANNEL_INTERACTION),
        )
    private val config = ClientConfig(backoffInitial = 50.milliseconds, backoffMax = 200.milliseconds)
    private val cleanup = mutableListOf<suspend () -> Unit>()

    @AfterTest
    fun tearDown() =
        runBlocking {
            cleanup.asReversed().forEach { it() }
        }

    @Test
    fun aKeptRequestIsSentAgainAndAnUnknownOneRefused() =
        runBlocking {
            val requests = Channel<String>(Channel.UNLIMITED)
            val refusals = Channel<ReplayRefused>(Channel.UNLIMITED)
            val client = client(desktop(requests, refusals).port)
            val replayed = CompletableDeferred<Unit>()
            client.replays.keep("kept") {
                replayed.complete(Unit)
                null
            }
            client.awaitState(ClientState.Connected)

            requests.send("kept")
            requests.send("unknown")

            withTimeout(5.seconds) { replayed.await() }
            val refused = withTimeout(5.seconds) { refusals.receive() }
            assertEquals("unknown", refused.request_id)
            assertEquals(RecentRequests.NOT_KEPT, refused.reason)
        }

    @Test
    fun aRequestTheModuleCannotSendAgainIsRefusedWithItsReason() =
        runBlocking {
            val requests = Channel<String>(Channel.UNLIMITED)
            val refusals = Channel<ReplayRefused>(Channel.UNLIMITED)
            val client = client(desktop(requests, refusals).port)
            client.replays.keep("one-shot") { "its body could be read only once" }
            client.replays.keep("broken") { error("client closed") }
            client.awaitState(ClientState.Connected)

            requests.send("one-shot")
            requests.send("broken")

            assertEquals("its body could be read only once", withTimeout(5.seconds) { refusals.receive() }.reason)
            assertEquals("The app could not send it again: client closed", withTimeout(5.seconds) { refusals.receive() }.reason)
        }

    @Test
    fun theRequestsAreForgottenWhenTheSessionEnds() =
        runBlocking {
            val requests = Channel<String>(Channel.UNLIMITED)
            val refusals = Channel<ReplayRefused>(Channel.UNLIMITED)
            val desktop = desktop(requests, refusals)
            val client = client(desktop.port)
            client.awaitState(ClientState.Connected)
            client.replays.keep("kept") { null }
            desktop.stop()
            client.awaitState(ClientState.BackingOff)

            assertEquals(RecentRequests.NOT_KEPT, client.replays.replay("kept"))
        }

    @Test
    fun onlyTheLastRequestsAreKept() {
        val recent = RecentRequests(capacity = 2)
        recent.keep("a") { null }
        recent.keep("b") { null }
        recent.keep("a") { null }
        recent.keep("c") { null }

        assertEquals(RecentRequests.NOT_KEPT, recent.replay("b"))
        assertNull(recent.replay("a"))
        assertNull(recent.replay("c"))
    }

    /** Sends each id of [requests] as a `ReplayRequest`, and puts each `ReplayRefused` the device sends in [refusals]. */
    private suspend fun desktop(
        requests: Channel<String>,
        refusals: Channel<ReplayRefused>,
    ): StandInDesktop {
        val desktop =
            StandInDesktop.start {
                handshake()
                val reader =
                    launch {
                        for (frame in incoming) {
                            val envelope = Envelope.ADAPTER.decode((frame as? Frame.Binary ?: continue).readBytes())
                            if (envelope.type == "ReplayRefused") refusals.send(ReplayRefused.ADAPTER.decode(envelope.payload))
                        }
                    }
                for (id in requests) {
                    val envelope =
                        Envelope(
                            channel = Protocol.CHANNEL_INTERACTION,
                            type = "ReplayRequest",
                            payload = ReplayRequest(request_id = id).encodeByteString(),
                        )
                    send(Frame.Binary(true, envelope.encode()))
                }
                reader.join()
            }
        cleanup += { runCatching { desktop.stop() } }
        return desktop
    }

    private fun client(port: Int): MonitorClient {
        val client = MonitorClient({ hello }, { Endpoint(LOOPBACK, port) }, config, {})
        cleanup += { client.stopAndJoin() }
        client.start()
        return client
    }
}
