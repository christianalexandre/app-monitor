package appmonitor.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.breakpoints.ABORT_HELD
import appmonitor.desktop.feature.breakpoints.BREAKPOINT_HOST
import appmonitor.desktop.feature.breakpoints.BREAKPOINT_NAME
import appmonitor.desktop.feature.breakpoints.BREAKPOINT_PATH
import appmonitor.desktop.feature.breakpoints.HELD_BODY
import appmonitor.desktop.feature.breakpoints.HELD_STATUS
import appmonitor.desktop.feature.breakpoints.HELD_URL
import appmonitor.desktop.feature.breakpoints.NEW_BREAKPOINT
import appmonitor.desktop.feature.breakpoints.RESUME_HELD
import appmonitor.desktop.feature.breakpoints.SAVE_BREAKPOINT
import appmonitor.desktop.feature.breakpoints.TOGGLE_BREAKPOINT
import appmonitor.desktop.feature.traffic.BREAKPOINTS
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.ServerConfig
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.protocol.Protocol
import appmonitor.v1.BreakpointAbort
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointResume
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointRules
import appmonitor.v1.BreakpointStage
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals

// ADR-0119: a breakpoint made and turned off and on in the window, and the calls it holds resumed edited or aborted there.
@OptIn(ExperimentalTestApi::class)
class BreakpointsUiTest {
    @Test
    fun `a breakpoint made in the window holds a call that is resumed edited and one that is aborted`() =
        withStorage { storage ->
            runBlocking {
                val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
                val port = server.start()
                val live = LiveTraffic()
                val store = TrafficStore(storage, live)
                val collecting = launch(Dispatchers.Default) { store.collect(server.sessions) }
                val received = Channel<Envelope>(Channel.UNLIMITED)
                val toSend = Channel<BreakpointHit>(Channel.UNLIMITED)
                val device =
                    launch(Dispatchers.Default) {
                        HttpClient(CIO) { install(WebSockets) }.use { client ->
                            client.webSocket(host = "127.0.0.1", port = port, path = Protocol.WEBSOCKET_PATH) {
                                val hello = HELLO.copy(channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_INTERACTION))
                                send(Frame.Binary(true, Envelope(Protocol.CHANNEL_CORE, "Hello", hello.encodeByteString(), 1).encode()))
                                launch {
                                    for (hit in toSend) {
                                        val envelope = Envelope(Protocol.CHANNEL_INTERACTION, "BreakpointHit", hit.encodeByteString(), 2)
                                        send(Frame.Binary(true, envelope.encode()))
                                    }
                                }
                                // Mock rules are pushed beside the breakpoints, in no set order (ADR-0118).
                                for (frame in incoming) {
                                    val envelope = Envelope.ADAPTER.decode((frame as Frame.Binary).readBytes())
                                    if (envelope.type != "MockRules") received.send(envelope)
                                }
                            }
                        }
                    }

                // Polled through [await]: in the window's test it runs the window's writes while it waits.
                var await: (() -> Boolean) -> Unit = { done -> runBlocking { withTimeout(TIMEOUT_MS) { while (!done()) delay(10) } } }

                fun next(type: String): Envelope {
                    var envelope: Envelope? = null
                    await { received.tryReceive().getOrNull()?.also { envelope = it } != null }
                    return checkNotNull(envelope).also { assertEquals(type, it.type) }
                }

                fun nextRules(): List<BreakpointRule> = BreakpointRules.ADAPTER.decode(next("BreakpointRules").payload).rules

                try {
                    next("HelloAck")
                    assertEquals(emptyList(), nextRules())

                    runDesktopComposeUiTest(width = 1280, height = 800) {
                        await = { done -> waitUntil(timeoutMillis = TIMEOUT_MS, condition = done) }
                        setContent {
                            AppMonitorApp(
                                storage,
                                "Listening",
                                appearance = TestAppearance,
                                heldCalls = store.heldCalls,
                                live = live,
                            )
                        }
                        awaitText("Pixel")
                        onNodeWithTag(BREAKPOINTS).performClick()
                        onNodeWithTag(NEW_BREAKPOINT).performClick()
                        awaitText("New breakpoint")
                        onNodeWithTag(BREAKPOINT_NAME).performTextReplacement("items")
                        onNodeWithTag(BREAKPOINT_HOST).performTextReplacement("api.example.com")
                        onNodeWithTag(BREAKPOINT_PATH).performTextReplacement("/v1/items/*")
                        onNodeWithTag(SAVE_BREAKPOINT).performClick()
                        awaitText("app · Any api.example.com/v1/items/* · holds the request and response · 60.00 s")
                        val id = runBlocking { storage.breakpoints() }.single().id
                        assertEquals(listOf(rule.copy(id = id)), nextRules())

                        onNodeWithTag(TOGGLE_BREAKPOINT + id).performClick()
                        awaitText("Off")
                        assertEquals(emptyList(), nextRules())
                        onNodeWithTag(TOGGLE_BREAKPOINT + id).performClick()
                        assertEquals(listOf(rule.copy(id = id)), nextRules())
                        onNodeWithText("Close").performClick()

                        toSend.trySend(requestHit)
                        awaitText("Request held")
                        awaitText("Breakpoints (1 held)")
                        onNodeWithTag(HELD_URL).performTextReplacement("https://api.example.com/v1/items/43")
                        onNodeWithTag(HELD_BODY).performTextReplacement("""{"n":2}""")
                        screenshot("breakpoint.png")
                        onNodeWithTag(RESUME_HELD).performClick()
                        assertEquals(
                            BreakpointResume(
                                request_id = "req-1",
                                stage = BreakpointStage.BREAKPOINT_STAGE_REQUEST,
                                url = "https://api.example.com/v1/items/43",
                                body = """{"n":2}""".encodeUtf8(),
                            ),
                            BreakpointResume.ADAPTER.decode(next("BreakpointResume").payload),
                            "the masked Authorization left alone is not sent back",
                        )
                        awaitText("Breakpoints")

                        toSend.trySend(responseHit)
                        awaitText("Response held")
                        onNodeWithTag(HELD_STATUS).performTextReplacement("503")
                        onNodeWithTag(ABORT_HELD).performClick()
                        assertEquals(
                            BreakpointAbort("req-1", BreakpointStage.BREAKPOINT_STAGE_RESPONSE, "Aborted from App Monitor"),
                            BreakpointAbort.ADAPTER.decode(next("BreakpointAbort").payload),
                        )
                        waitUntil(timeoutMillis = TIMEOUT_MS) {
                            store.heldCalls.held.value
                                .isEmpty()
                        }
                    }
                } finally {
                    device.cancel()
                    collecting.cancel()
                    server.stop()
                }
            }
        }

    private companion object {
        val rule = BreakpointRule(host = "api.example.com", path = "/v1/items/*", request = true, response = true, timeout_ms = 60_000)

        val request =
            RequestStarted(
                request_id = "req-1",
                method = "POST",
                url = "https://api.example.com/v1/items/42",
                headers = listOf(Header("Authorization", "██"), Header("Content-Type", "application/json")),
                body = """{"n":1}""".encodeUtf8(),
            )

        val requestHit =
            BreakpointHit(rule_id = 1, stage = BreakpointStage.BREAKPOINT_STAGE_REQUEST, request = request, timeout_ms = 60_000)

        val responseHit =
            BreakpointHit(
                rule_id = 1,
                stage = BreakpointStage.BREAKPOINT_STAGE_RESPONSE,
                request = request,
                response = ResponseReceived(request_id = "req-1", status_code = 200, body = """{"id":42}""".encodeUtf8()),
                timeout_ms = 60_000,
            )
    }
}
