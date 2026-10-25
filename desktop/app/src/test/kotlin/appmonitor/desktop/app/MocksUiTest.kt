package appmonitor.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.mocks.CONFIRM_MOCK
import appmonitor.desktop.feature.mocks.MOCK_BODY
import appmonitor.desktop.feature.mocks.MOCK_STATUS
import appmonitor.desktop.feature.mocks.SAVE_MOCK
import appmonitor.desktop.feature.traffic.MOCKS
import appmonitor.desktop.feature.traffic.MOCK_THIS
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.ServerConfig
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.desktop.storage.MockSource
import appmonitor.desktop.storage.MockWrite
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.HelloAck
import appmonitor.v1.MockFailure
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import appmonitor.v1.MockRules
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalTestApi::class)
class MocksUiTest {
    @Test
    fun `a rule made from a gzip exchange in the window reaches a connected device as one envelope`() =
        withStorage { storage ->
            val live = LiveTraffic()
            val recorded = HELLO.copy(device_id = "d2", device_name = "Recorded")
            live.record(
                envelope(RequestStarted(request_id = "r1", method = "GET", url = URL)),
                envelope(
                    ResponseReceived(
                        request_id = "r1",
                        status_code = 200,
                        headers =
                            listOf(
                                Header("Content-Type", "application/json"),
                                Header("Content-Encoding", "gzip"),
                                Header("Content-Length", "11"),
                            ),
                        body = gzip("""{"items":[]}"""),
                    ),
                ),
                hello = recorded,
            )
            runBlocking {
                val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
                val port = server.start()
                val collecting = launch(Dispatchers.Default) { TrafficStore(storage, live).collect(server.sessions) }
                val received = Channel<Envelope>(Channel.UNLIMITED)
                val device =
                    launch(Dispatchers.Default) {
                        HttpClient(CIO) { install(WebSockets) }.use { client ->
                            client.webSocket(host = "127.0.0.1", port = port, path = Protocol.WEBSOCKET_PATH) {
                                val hello = HELLO.copy(channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_INTERACTION))
                                send(Frame.Binary(true, Envelope(Protocol.CHANNEL_CORE, "Hello", hello.encodeByteString(), 1).encode()))
                                // Breakpoints are pushed beside the rules, in no set order (ADR-0118).
                                for (frame in incoming) {
                                    val envelope = Envelope.ADAPTER.decode((frame as Frame.Binary).readBytes())
                                    if (envelope.type != "BreakpointRules") received.send(envelope)
                                }
                            }
                        }
                    }
                try {
                    withTimeout(TIMEOUT_MS) {
                        HelloAck.ADAPTER.decode(received.receive().payload)
                        assertEquals(emptyList(), MockRules.ADAPTER.decode(received.receive().payload).rules)
                    }

                    runDesktopComposeUiTest(width = 1280, height = 800) {
                        setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }
                        awaitText("Recorded")
                        onNodeWithText("Recorded").performClick()
                        awaitText(URL)
                        onNodeWithText(URL).performClick()
                        awaitText("Mock this")
                        onNodeWithTag(MOCK_THIS).performClick()
                        awaitText("New mock rule")
                        onNodeWithText("GET /v1/items").assertExists()
                        onNodeWithText("App: app ▾").assertExists()
                        onNodeWithTag(MOCK_STATUS).performTextReplacement("503")
                        onNodeWithTag(MOCK_BODY).assertTextContains("""{"items":[]}""")
                        onNodeWithTag(SAVE_MOCK).performClick()
                        awaitText("app · GET api.example.com/v1/items → 503")
                    }

                    val sent = withTimeout(TIMEOUT_MS) { received.receive() }
                    assertEquals(Protocol.CHANNEL_INTERACTION to "MockRules", sent.channel to sent.type)
                    val stored = storage.mocks().single()
                    assertEquals(MockSource.WINDOW to false, stored.source to stored.pending)
                    assertEquals(
                        listOf(
                            MockRule(
                                id = stored.id,
                                method = "GET",
                                host = "api.example.com",
                                path = "/v1/items",
                                response =
                                    MockResponse(
                                        status_code = 503,
                                        headers = listOf(Header("Content-Type", "application/json")),
                                        body = """{"items":[]}""".encodeUtf8(),
                                    ),
                            ),
                        ),
                        MockRules.ADAPTER.decode(sent.payload).rules,
                    )
                } finally {
                    device.cancel()
                    collecting.cancel()
                    server.stop()
                }
            }
        }

    @Test
    fun `a rule from mcp waits for a confirmation and rules past one frame are marked`() =
        withStorage { storage ->
            val big = MockResponse(status_code = 200, body = ByteString.of(*ByteArray(500 * 1024)))
            runBlocking {
                listOf("feed page 1", "feed page 2", "feed page 3").forEachIndexed { index, name ->
                    val rule = MockRule(method = "GET", host = "api.example.com", path = "/v1/feed/${index + 1}", response = big)
                    assertIs<MockWrite.Saved>(storage.createMock("app", name, rule, MockSource.WINDOW))
                }
                val offline = MockRule(host = "api.example.com", path = "/v1/orders/*", delay_ms = 1_500, failure = MockFailure("offline"))
                assertIs<MockWrite.Saved>(storage.createMock("app", "orders offline", offline, MockSource.MCP))
            }
            val agent = runBlocking { storage.mocks() }.single { it.source == MockSource.MCP }.id

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance) }
                awaitText("Mocks (1 to confirm)")
                onNodeWithTag(MOCKS).performClick()
                awaitText("orders offline")
                onNodeWithText("From MCP: not sent until you confirm it").assertExists()
                onNodeWithText("app · Any api.example.com/v1/orders/* → fails: offline · 1.50 s").assertExists()
                onNodeWithText("Not sent: past one frame, with the rules before it").assertExists()
                screenshot("mocks.png")

                onNodeWithTag(CONFIRM_MOCK + agent).performClick()
                awaitText("Mocks")
                waitUntil(timeoutMillis = TIMEOUT_MS) { runBlocking { storage.mocks() }.none { it.pending } }
            }
        }

    private fun gzip(text: String): ByteString {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray().toByteString()
    }
}
