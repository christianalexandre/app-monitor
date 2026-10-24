package appmonitor.sdk.ktor

import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.v1.CaptureLayer
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import appmonitor.v1.WebSocketFrame.Direction.DIRECTION_RECEIVED
import appmonitor.v1.WebSocketFrame.Direction.DIRECTION_SENT
import appmonitor.v1.WebSocketFrame.Opcode
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frames of a session against [EchoServer] through [engine], one subclass per engine (ADR-0070). [answersCloseItself]:
 * the engine replies to the peer's close without a frame the plugin sees (OkHttp). [losesClose]: the engine may end
 * the session by cancellation before the peer's close reaches it, capture on or off (Darwin).
 */
abstract class WebSocketCaptureTest(
    private val engine: HttpClientEngineFactory<*>,
    private val answersCloseItself: Boolean = false,
    private val losesClose: Boolean = false,
) {
    private val cleanup = mutableListOf<suspend () -> Unit>()

    @AfterTest
    fun tearDown() =
        runBlocking {
            cleanup.asReversed().forEach { it() }
        }

    private suspend fun server() = EchoServer.start().also { cleanup += { it.stop() } }

    private fun client(sink: CaptureSink) =
        HttpClient(engine) {
            install(WebSockets)
            install(appMonitorKtor(sink))
        }.also { cleanup += { it.close() } }

    /** Sends `hello`, 20 bytes and `close`, reading each answer; returns what the app received. */
    private suspend fun HttpClient.converse(port: Int): List<Frame> {
        val received = mutableListOf<Frame>()
        try {
            webSocket("ws://127.0.0.1:$port/echo?token=abc") {
                send(Frame.Text("hello"))
                received += incoming.receive()
                send(Frame.Binary(true, ByteArray(20) { it.toByte() }))
                received += incoming.receive()
                send(Frame.Text("close"))
                incoming.consumeEach { }
            }
        } catch (e: CancellationException) {
            if (!losesClose) throw e
        }
        return received
    }

    @Test
    fun framesBothWaysAndTheCloseCarryTheUpgradeRequestId() =
        runBlocking {
            val sink = ConcurrentSink(AppMonitorOptions(maxBodyBytes = 8, maskedQueryParameters = setOf("token")))
            val received = client(sink).converse(server().port)

            assertEquals("hello", (received[0] as Frame.Text).readText())
            assertEquals(20, received[1].readBytes().size)
            val started = RequestStarted.ADAPTER.decode(sink.next("RequestStarted").payload)
            assertTrue(started.url.endsWith("/echo?token=██"), started.url)
            assertEquals(CaptureLayer.CAPTURE_LAYER_ADAPTER, started.capture_layer)
            val response = ResponseReceived.ADAPTER.decode(sink.next("ResponseReceived").payload)
            assertEquals(101, response.status_code)
            val id = started.request_id
            val hello = "hello".encodeUtf8()
            val bytes = ByteArray(20) { it.toByte() }.toByteString()
            val close = (byteArrayOf(0x03, 0xE8.toByte()) + "bye".encodeToByteArray()).toByteString()
            val conversation =
                listOf(
                    WebSocketFrame(id, DIRECTION_SENT, Opcode.OPCODE_TEXT, hello, false, 5),
                    WebSocketFrame(id, DIRECTION_RECEIVED, Opcode.OPCODE_TEXT, hello, false, 5),
                    WebSocketFrame(id, DIRECTION_SENT, Opcode.OPCODE_BINARY, bytes.substring(0, 8), true, 20),
                    WebSocketFrame(id, DIRECTION_RECEIVED, Opcode.OPCODE_BINARY, bytes.substring(0, 8), true, 20),
                    WebSocketFrame(id, DIRECTION_SENT, Opcode.OPCODE_TEXT, "close".encodeUtf8(), false, 5),
                )
            val closing =
                listOfNotNull(
                    WebSocketFrame(id, DIRECTION_RECEIVED, Opcode.OPCODE_CLOSE, close, false, 5),
                    WebSocketFrame(id, DIRECTION_SENT, Opcode.OPCODE_CLOSE, close, false, 5).takeUnless { answersCloseItself },
                )
            val frames = sink.frames()
            if (losesClose && frames.size < conversation.size + closing.size) {
                assertEquals(conversation + closing.take(frames.size - conversation.size), frames)
            } else {
                assertEquals(conversation + closing, frames)
            }
        }

    @Test
    fun aSessionOpenedBeforeStartIsNotCaptured() =
        runBlocking {
            val sink = ConcurrentSink(options = null)
            val received = client(sink).converse(server().port)

            assertEquals(2, received.size)
            delay(100)
            assertNull(sink.pending)
        }
}

class CioWebSocketCaptureTest : WebSocketCaptureTest(CIO)
