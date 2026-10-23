package appmonitor.desktop.app

import appmonitor.desktop.app.store.CapturedBody
import appmonitor.desktop.app.store.CapturedFrame
import appmonitor.desktop.app.store.TrafficState
import appmonitor.tools.fakeclient.Fixtures
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.WebSocketFrame
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class TrafficStateTest {
    private val hello = Hello.ADAPTER.decode(Fixtures.hello.payload)
    private val connected = TrafficState().connected(SESSION, hello)

    @Test
    fun `builds requests from the fixture traffic`() {
        val (started, response, failed, _) = Fixtures.traffic
        val state =
            connected
                .received(SESSION, started.copy(timestamp_ms = 1_000))
                .received(SESSION, response.copy(timestamp_ms = 1_250))
                .received(SESSION, failed.copy(timestamp_ms = 2_000))

        val (post, timeout) = state.exchangesOf(SESSION)
        assertEquals("POST", post.method)
        assertEquals("https://api.example.com/v1/items?page=2", post.url)
        assertEquals(201, post.statusCode)
        assertEquals(250, post.durationMs)
        assertEquals(CapturedBody("""{"name":"café"}""".encodeUtf8(), originalSize = 16), post.requestBody)
        assertEquals(CapturedBody(post.responseBody.bytes, truncated = true, originalSize = 2_097_152), post.responseBody)
        assertEquals("req-0002", timeout.requestId)
        assertNull(timeout.method, "RequestFailed without RequestStarted still shows up")
        assertEquals("java.net.SocketTimeoutException: timeout", timeout.error)
    }

    @Test
    fun `attaches websocket frames to their exchange in order`() {
        val frame = Fixtures.traffic.last()
        val decoded = WebSocketFrame.ADAPTER.decode(frame.payload)
        val cut = decoded.copy(payload = "pi".encodeUtf8(), payload_truncated = true)
        val state =
            connected
                .received(SESSION, frame.copy(timestamp_ms = 1_000))
                .received(SESSION, frame.copy(payload = cut.encodeByteString(), timestamp_ms = 1_100))

        val exchange = state.exchangesOf(SESSION).single()
        assertEquals("req-0003", exchange.requestId)
        assertEquals(
            listOf(
                CapturedFrame(decoded.direction, decoded.opcode, CapturedBody("ping".encodeUtf8(), originalSize = 4), 1_000),
                CapturedFrame(decoded.direction, decoded.opcode, CapturedBody("pi".encodeUtf8(), true, 4), 1_100),
            ),
            exchange.frames,
        )
    }

    @Test
    fun `keeps sessions apart`() {
        val started = Fixtures.traffic.first()
        val state = connected.connected("other", hello).received(SESSION, started).received("other", started)

        assertEquals(1, state.exchangesOf(SESSION).size)
        assertEquals(1, state.exchangesOf("other").size)
    }

    @Test
    fun `ignores other channels and undecodable payloads`() {
        val broken = Fixtures.traffic.first().copy(payload = "ÿÿÿ".encodeUtf8())

        assertSame(connected, connected.received(SESSION, Fixtures.hello))
        assertSame(connected, connected.received(SESSION, broken))
        assertSame(connected, connected.received(SESSION, Envelope(channel = "network", type = "Unknown")))
    }

    @Test
    fun `marks a device disconnected and keeps its traffic`() {
        val state = connected.received(SESSION, Fixtures.traffic.first()).disconnected(SESSION)

        assertFalse(state.devices.single().connected)
        assertEquals(1, state.exchangesOf(SESSION).size)
    }

    private companion object {
        const val SESSION = "session"
    }
}
