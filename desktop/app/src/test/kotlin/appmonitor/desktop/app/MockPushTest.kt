package appmonitor.desktop.app

import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.store.mockRulesEnvelope
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.ServerConfig
import appmonitor.desktop.storage.MockSource
import appmonitor.desktop.storage.MockWrite
import appmonitor.desktop.storage.Storage
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.HelloAck
import appmonitor.v1.MockFailure
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import appmonitor.v1.MockRules
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MockPushTest {
    @Test
    fun `a device that lists interaction gets its app's active rules after HelloAck and on every change`() =
        withStorage { storage ->
            val window = saved(runBlocking { storage.createMock(HELLO.app_id, "items down", unavailable, MockSource.WINDOW) })
            val agent = saved(runBlocking { storage.createMock(HELLO.app_id, "orders reset", reset, MockSource.MCP) })
            withDesktop(storage) { port ->
                connect(port, listOf(Protocol.CHANNEL_NETWORK, Protocol.CHANNEL_INTERACTION)) {
                    assertEquals(
                        listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK, Protocol.CHANNEL_INTERACTION),
                        HelloAck.ADAPTER.decode(receiveEnvelope().payload).channels,
                    )
                    val first = nextMockRules()
                    assertEquals(Protocol.CHANNEL_INTERACTION to "MockRules", first.channel to first.type)
                    assertEquals(
                        listOf(unavailable.copy(id = window)),
                        MockRules.ADAPTER.decode(first.payload).rules,
                        "a pending rule is not sent",
                    )

                    saved(storage.confirmMock(agent))
                    assertEquals(
                        listOf(window, agent),
                        MockRules.ADAPTER
                            .decode(nextMockRules().payload)
                            .rules
                            .map { it.id },
                    )
                }
            }
        }

    @Test
    fun `a device that does not list interaction is sent nothing after HelloAck`() =
        withStorage { storage ->
            saved(runBlocking { storage.createMock(HELLO.app_id, "items down", unavailable, MockSource.WINDOW) })
            withDesktop(storage) { port ->
                connect(port, listOf(Protocol.CHANNEL_NETWORK)) {
                    assertEquals(
                        listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK),
                        HelloAck.ADAPTER.decode(receiveEnvelope().payload).channels,
                    )
                    assertNull(withTimeoutOrNull(QUIET_MS) { incoming.receive() })
                }
            }
        }

    @Test
    fun `rules past one frame are left out from the first that does not fit`() {
        val big = unavailable.copy(response = MockResponse(status_code = 200, body = ByteString.of(*ByteArray(500 * 1024))))
        val rules = listOf(big.copy(id = 1), big.copy(id = 2), big.copy(id = 3), unavailable.copy(id = 4))

        val envelope = mockRulesEnvelope(rules, atMs = 1)

        assertEquals(
            listOf(1L, 2L),
            MockRules.ADAPTER
                .decode(envelope.payload)
                .rules
                .map { it.id },
        )
        assertTrue(envelope.encode().size <= Protocol.MAX_FRAME_BYTES)
    }

    private fun withDesktop(
        storage: Storage,
        block: suspend (port: Int) -> Unit,
    ) = runBlocking {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        try {
            val port = server.start()
            val collecting = launch(Dispatchers.Default) { TrafficStore(storage).collect(server.sessions) }
            withTimeout(TIMEOUT_MS) { block(port) }
            collecting.cancel()
        } finally {
            server.stop()
        }
    }

    private suspend fun connect(
        port: Int,
        channels: List<String>,
        block: suspend DefaultClientWebSocketSession.() -> Unit,
    ) {
        HttpClient(CIO) { install(WebSockets) }.use { client ->
            client.webSocket(host = "127.0.0.1", port = port, path = Protocol.WEBSOCKET_PATH) {
                val hello = HELLO.copy(channels = listOf(Protocol.CHANNEL_CORE) + channels)
                send(Frame.Binary(true, Envelope(Protocol.CHANNEL_CORE, "Hello", hello.encodeByteString(), 1).encode()))
                block()
            }
        }
    }

    // Breakpoints are pushed beside the rules, in no set order (ADR-0118).
    private suspend fun DefaultClientWebSocketSession.nextMockRules(): Envelope {
        while (true) {
            val envelope = receiveEnvelope()
            if (envelope.type != "BreakpointRules") return envelope
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveEnvelope(): Envelope =
        Envelope.ADAPTER.decode((incoming.receive() as Frame.Binary).readBytes())

    private fun saved(write: MockWrite): Long = assertIs<MockWrite.Saved>(write).id

    private companion object {
        const val QUIET_MS = 500L

        val unavailable =
            MockRule(
                host = "api.example.com",
                path = "/v1/items/*",
                response = MockResponse(status_code = 503, body = """{"error":"maintenance"}""".encodeUtf8()),
            )

        val reset = MockRule(method = "POST", host = "api.example.com", path = "/v1/orders", failure = MockFailure("reset"))
    }
}
