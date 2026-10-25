package appmonitor.desktop.app

import appmonitor.desktop.app.store.HeldCall
import appmonitor.desktop.app.store.HeldCalls
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.store.resumeOf
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.ServerConfig
import appmonitor.desktop.storage.BreakpointChange
import appmonitor.desktop.storage.BreakpointWrite
import appmonitor.desktop.storage.Storage
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
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// ADR-0118: breakpoints sent like mock rules, and a stand-in device's held call resumed, aborted or let go.
class HeldCallsTest {
    @Test
    fun `a device that lists interaction gets its app's breakpoints after HelloAck and on every change`() =
        withStorage { storage ->
            val id = saved(runBlocking { storage.createBreakpoint(HELLO.app_id, "items", items) })
            withDesktop(storage) { port, _ ->
                connect(port) {
                    assertEquals(listOf(items.copy(id = id)), nextBreakpointRules().rules)
                    saved(storage.updateBreakpoint(id, BreakpointChange(enabled = false)))
                    assertEquals(emptyList(), nextBreakpointRules().rules)
                }
            }
        }

    @Test
    fun `a held request resumed edited carries only the changed fields`() =
        withStorage { storage ->
            withDesktop(storage) { port, held ->
                connect(port) {
                    sendHit(requestHit)
                    val call = held.held.first { it.isNotEmpty() }.single()
                    assertEquals(BreakpointStage.BREAKPOINT_STAGE_REQUEST, call.stage)

                    val edit = call.unedited.copy(url = "https://api.example.com/v1/items/43", body = """{"n":2}""".encodeUtf8())
                    assertTrue(held.resume(call, edit))

                    assertEquals(
                        BreakpointResume(
                            request_id = "req-1",
                            stage = BreakpointStage.BREAKPOINT_STAGE_REQUEST,
                            url = "https://api.example.com/v1/items/43",
                            body = """{"n":2}""".encodeUtf8(),
                        ),
                        BreakpointResume.ADAPTER.decode(next("BreakpointResume").payload),
                        "the masked Authorization is not sent back",
                    )
                    assertEquals(emptyList(), held.held.value)
                    assertFalse(held.resume(call), "a call is resumed once")
                }
            }
        }

    @Test
    fun `an aborted call fails on the device with the message`() =
        withStorage { storage ->
            withDesktop(storage) { port, held ->
                connect(port) {
                    sendHit(responseHit)
                    val call = held.held.first { it.isNotEmpty() }.single()
                    assertTrue(held.abort(call, "aborted on the desktop"))
                    assertEquals(
                        BreakpointAbort("req-1", BreakpointStage.BREAKPOINT_STAGE_RESPONSE, "aborted on the desktop"),
                        BreakpointAbort.ADAPTER.decode(next("BreakpointAbort").payload),
                    )
                }
            }
        }

    @Test
    fun `a call is let go after its timeout and when its session ends`() =
        withStorage { storage ->
            withDesktop(storage) { port, held ->
                connect(port) {
                    sendHit(requestHit.copy(timeout_ms = 200))
                    val call = held.held.first { it.isNotEmpty() }.single()
                    held.held.first { it.isEmpty() }
                    assertFalse(held.resume(call), "the device went on at its timeout")

                    sendHit(responseHit)
                    held.held.first { it.isNotEmpty() }
                }
                held.held.first { it.isEmpty() }
            }
        }

    @Test
    fun `a response resume sets and removes headers by name and keeps a truncated body it did not change`() {
        val truncated =
            responseHit.copy(
                response =
                    responseHit.response!!.copy(
                        headers = listOf(Header("Set-Cookie", "a=1"), Header("Content-Type", "application/json"), Header("ETag", "1")),
                        body_truncated = true,
                    ),
            )
        val call = HeldCall("s", "app", truncated, heldAtMs = 0)
        val edit =
            call.unedited.copy(
                statusCode = 404,
                headers = listOf(Header("set-cookie", "a=1"), Header("Set-Cookie", "b=2"), Header("Content-Type", "application/json")),
            )

        assertEquals(
            BreakpointResume(
                request_id = "req-1",
                stage = BreakpointStage.BREAKPOINT_STAGE_RESPONSE,
                status_code = 404,
                set_headers = listOf(Header("set-cookie", "a=1"), Header("Set-Cookie", "b=2")),
                removed_headers = listOf("ETag"),
            ),
            resumeOf(call, edit),
        )
        assertEquals(BreakpointResume("req-1", BreakpointStage.BREAKPOINT_STAGE_RESPONSE), resumeOf(call, call.unedited))
    }

    @Test
    fun `a hit without a request, a stage or a response at the response stage is not held`() =
        withStorage { storage ->
            withDesktop(storage) { port, held ->
                connect(port) {
                    sendHit(requestHit.copy(request = null))
                    sendHit(requestHit.copy(stage = BreakpointStage.BREAKPOINT_STAGE_UNSPECIFIED))
                    sendHit(responseHit.copy(response = null))
                    sendHit(responseHit)
                    assertEquals(listOf(responseHit), held.held.first { it.isNotEmpty() }.map { it.hit }, "envelopes are taken in order")
                }
            }
        }

    private fun withDesktop(
        storage: Storage,
        block: suspend (port: Int, held: HeldCalls) -> Unit,
    ) = runBlocking {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val store = TrafficStore(storage)
        try {
            val port = server.start()
            val collecting = launch(Dispatchers.Default) { store.collect(server.sessions) }
            withTimeout(TIMEOUT_MS) { block(port, store.heldCalls) }
            collecting.cancel()
        } finally {
            server.stop()
        }
    }

    private suspend fun connect(
        port: Int,
        block: suspend DefaultClientWebSocketSession.() -> Unit,
    ) {
        HttpClient(CIO) { install(WebSockets) }.use { client ->
            client.webSocket(host = "127.0.0.1", port = port, path = Protocol.WEBSOCKET_PATH) {
                val hello = HELLO.copy(channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK, Protocol.CHANNEL_INTERACTION))
                send(Frame.Binary(true, Envelope(Protocol.CHANNEL_CORE, "Hello", hello.encodeByteString(), 1).encode()))
                assertEquals("HelloAck", receiveEnvelope().type)
                block()
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.sendHit(hit: BreakpointHit) =
        send(Frame.Binary(true, Envelope(Protocol.CHANNEL_INTERACTION, "BreakpointHit", hit.encodeByteString(), 2).encode()))

    // Mock rules and breakpoints are pushed apart, in no set order.
    private suspend fun DefaultClientWebSocketSession.next(type: String): Envelope {
        while (true) {
            val envelope = receiveEnvelope()
            if (envelope.type == type) return envelope
        }
    }

    private suspend fun DefaultClientWebSocketSession.nextBreakpointRules(): BreakpointRules =
        BreakpointRules.ADAPTER.decode(next("BreakpointRules").payload)

    private suspend fun DefaultClientWebSocketSession.receiveEnvelope(): Envelope =
        Envelope.ADAPTER.decode((incoming.receive() as Frame.Binary).readBytes())

    private fun saved(write: BreakpointWrite): Long = assertIs<BreakpointWrite.Saved>(write).id

    private companion object {
        val items = BreakpointRule(host = "api.example.com", path = "/v1/items/*", request = true, response = true, timeout_ms = 60_000)

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
                response = ResponseReceived(request_id = "req-1", status_code = 200, body = ByteString.EMPTY),
                timeout_ms = 60_000,
            )
    }
}
