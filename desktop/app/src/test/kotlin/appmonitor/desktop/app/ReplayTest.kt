package appmonitor.desktop.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.store.ReplayKey
import appmonitor.desktop.app.store.ReplaySent
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.app.ui.REPLAYED
import appmonitor.desktop.app.ui.SEND_AGAIN
import appmonitor.desktop.app.ui.SHOW_ORIGINAL
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.ServerConfig
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.ReplayRefused
import appmonitor.v1.ReplayRequest
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import com.squareup.wire.Message
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

// ADR-0120: "Send again" reaches a stand-in device, its replay is shown linked to the original, a refusal is shown.
@OptIn(ExperimentalTestApi::class)
class ReplayTest {
    @Test
    fun `send again from the window reaches the device and its replay links to the original`() =
        withStorage { storage ->
            withDevice(storage, INTERACTION) { store, device ->
                device.send("RequestStarted", original)
                device.send("ResponseReceived", ResponseReceived(request_id = "req-1", status_code = 200))
                runDesktopComposeUiTest(width = 1280, height = 800) {
                    device.await = { done -> waitUntil(timeoutMillis = TIMEOUT_MS, condition = done) }
                    setContent { AppMonitorApp(storage, "Listening", replays = store.replays) }
                    awaitText(original.url)
                    onNodeWithText(original.url).performClick()
                    onNodeWithTag(SEND_AGAIN).performClick()
                    assertEquals(ReplayRequest("req-1"), ReplayRequest.ADAPTER.decode(device.next("ReplayRequest").payload))
                    awaitText("Sent to the app; its call shows as a replay")

                    device.send("RequestStarted", original.copy(request_id = "req-2", replay_of = "req-1"))
                    waitUntil(timeoutMillis = TIMEOUT_MS) { onAllTagged(REPLAYED + "req-2") }
                    onNodeWithTag(REPLAYED + "req-2", useUnmergedTree = true).performClick()
                    awaitText("Replay of an earlier request")
                    screenshot("replay.png")
                    onNodeWithTag(SHOW_ORIGINAL).performClick()
                    awaitText("Status 200", substring = true)

                    onNodeWithTag(SEND_AGAIN).performClick()
                    device.next("ReplayRequest")
                    device.send("ReplayRefused", ReplayRefused("req-1", "no longer kept by the SDK"))
                    awaitText("The app did not send it again: no longer kept by the SDK")
                }
            }
        }

    @Test
    fun `a replay is not sent to a device without interaction or once its session ended`() =
        withStorage { storage ->
            var sessionId = ""
            withDevice(storage, listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK)) { store, device ->
                device.send("RequestStarted", original)
                device.await { storage.sessionsNow().isNotEmpty() }
                sessionId = storage.sessionsNow().single().serverSessionId
                assertEquals(ReplaySent.NOT_TAKEN, store.replays.send(sessionId, "req-1"))
            }
            withDevice(storage, INTERACTION) { store, device ->
                device.send("RequestStarted", original)
                device.await { storage.sessionsNow().size == 2 }
                assertEquals(ReplaySent.SESSION_ENDED, store.replays.send(sessionId, "req-1"))
                val live = storage.sessionsNow().single { it.disconnectedAtMs == null }.serverSessionId
                assertEquals(ReplaySent.SENT, store.replays.send(live, "req-1"))
                device.next("ReplayRequest")
                device.send("ReplayRefused", ReplayRefused("req-1", "gone"))
                device.await {
                    store.replays.refused.value
                        .isNotEmpty()
                }
                assertEquals(mapOf(ReplayKey(live, "req-1") to "gone"), store.replays.refused.value)
                assertEquals(ReplaySent.SENT, store.replays.send(live, "req-1"))
                assertEquals(emptyMap(), store.replays.refused.value, "a new send clears the refusal")
            }
        }

    private class Device(
        private val outgoing: Channel<Envelope>,
        private val received: Channel<Envelope>,
    ) {
        // Polled through [await]: in the window's test it runs the window's writes while it waits.
        var await: (() -> Boolean) -> Unit = { done -> runBlocking { withTimeout(TIMEOUT_MS) { while (!done()) delay(10) } } }

        fun send(
            type: String,
            message: Message<*, *>,
        ) {
            val channel = if (type.startsWith("Replay")) Protocol.CHANNEL_INTERACTION else Protocol.CHANNEL_NETWORK
            outgoing.trySend(Envelope(channel, type, message.encodeByteString(), 2))
        }

        fun next(type: String): Envelope {
            var envelope: Envelope? = null
            await {
                received
                    .tryReceive()
                    .getOrNull()
                    ?.takeIf { it.type == type }
                    ?.also { envelope = it } != null
            }
            return checkNotNull(envelope)
        }
    }

    private fun withDevice(
        storage: appmonitor.desktop.storage.Storage,
        channels: List<String>,
        block: (TrafficStore, Device) -> Unit,
    ) = runBlocking {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val port = server.start()
        val store = TrafficStore(storage)
        val collecting = launch(Dispatchers.Default) { store.collect(server.sessions) }
        val outgoing = Channel<Envelope>(Channel.UNLIMITED)
        val received = Channel<Envelope>(Channel.UNLIMITED)
        val acked = Job()
        val socket = connect(port, channels, outgoing, received, acked)
        try {
            withTimeout(TIMEOUT_MS) { acked.join() }
            block(store, Device(outgoing, received))
        } finally {
            socket.cancel()
            collecting.cancel()
            server.stop()
        }
    }

    private fun CoroutineScope.connect(
        port: Int,
        channels: List<String>,
        outgoing: Channel<Envelope>,
        received: Channel<Envelope>,
        acked: CompletableJob,
    ) = launch(Dispatchers.Default) {
        HttpClient(CIO) { install(WebSockets) }.use { client ->
            client.webSocket(host = "127.0.0.1", port = port, path = Protocol.WEBSOCKET_PATH) {
                val hello = HELLO.copy(channels = channels)
                send(Frame.Binary(true, Envelope(Protocol.CHANNEL_CORE, "Hello", hello.encodeByteString(), 1).encode()))
                launch { for (envelope in outgoing) send(Frame.Binary(true, envelope.encode())) }
                for (frame in incoming) {
                    val envelope = Envelope.ADAPTER.decode((frame as Frame.Binary).readBytes())
                    if (envelope.type == "HelloAck") acked.complete()
                    received.send(envelope)
                }
            }
        }
    }

    private fun ComposeUiTest.onAllTagged(tag: String): Boolean =
        onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun appmonitor.desktop.storage.Storage.sessionsNow() = runBlocking { sessions(runId) }

    private companion object {
        val INTERACTION = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK, Protocol.CHANNEL_INTERACTION)

        val original = RequestStarted(request_id = "req-1", method = "GET", url = "https://api.example.com/v1/items/42")
    }
}
