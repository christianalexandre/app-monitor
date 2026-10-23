package appmonitor.sdk.okhttp

import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.v1.Header
import appmonitor.v1.WebSocketFrame.Direction
import appmonitor.v1.WebSocketFrame.Opcode
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppMonitorWebSocketFactoryTest {
    private val server = MockWebServer()

    @BeforeTest
    fun startServer() {
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.close()
    }

    @Test
    fun framesAreCapturedUnderTheUpgradeRequest() {
        val sink = RecordingSink()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(Echo()).build())
        val client = OkHttpClient.Builder().addInterceptor(AppMonitorInterceptor(sink)).build()
        val closed = CountDownLatch(1)
        var openedWith: WebSocket? = null
        val listener =
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    openedWith = webSocket
                    webSocket.send("hello")
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    webSocket.close(1000, "bye")
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    closed.countDown()
                }
            }

        val request =
            Request
                .Builder()
                .url(server.url("/ws"))
                .header("Authorization", "Bearer abc")
                .build()
        val socket = AppMonitorWebSocketFactory(client, sink).newWebSocket(request, listener)

        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertSame(socket, openedWith)
        val started = sink.started()
        assertContains(started.headers, Header("Authorization", MASK))
        val opened = sink.received()
        assertEquals(started.request_id, opened.request_id)
        assertEquals(101, opened.status_code)
        val frames = List(4) { sink.frame() }
        assertTrue(frames.all { it.request_id == started.request_id })
        assertEquals(
            listOf(
                Triple(Direction.DIRECTION_SENT, Opcode.OPCODE_TEXT, "hello"),
                Triple(Direction.DIRECTION_RECEIVED, Opcode.OPCODE_TEXT, "hello"),
                Triple(Direction.DIRECTION_SENT, Opcode.OPCODE_CLOSE, closePayload(1000, "bye")),
                Triple(Direction.DIRECTION_RECEIVED, Opcode.OPCODE_CLOSE, closePayload(1000, "bye")),
            ),
            frames.map { Triple(it.direction, it.opcode, it.payload.utf8()) },
        )
        assertEquals(0, sink.pending)
    }

    @Test
    fun aFrameOverTheLimitIsMarkedTruncatedWithItsSize() {
        val sink = RecordingSink(AppMonitorOptions(maxBodyBytes = 4))
        server.enqueue(MockResponse.Builder().webSocketUpgrade(Echo()).build())
        val received = CountDownLatch(1)
        val listener =
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    webSocket.send("hello world")
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    received.countDown()
                    webSocket.cancel()
                }
            }

        AppMonitorWebSocketFactory(OkHttpClient(), sink).newWebSocket(Request.Builder().url(server.url("/ws")).build(), listener)

        assertTrue(received.await(5, TimeUnit.SECONDS))
        sink.started()
        sink.received()
        val (sent, echoed) = List(2) { sink.frame() }
        listOf(sent, echoed).forEach {
            assertEquals("hell", it.payload.utf8())
            assertTrue(it.payload_truncated)
            assertEquals(11L, it.payload_size)
        }
    }

    @Test
    fun theUpgradeUrlUserInfoIsMasked() {
        val sink = RecordingSink()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(Echo()).build())
        val opened = CountDownLatch(1)
        val listener =
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    opened.countDown()
                    webSocket.cancel()
                }
            }
        val url =
            server
                .url("/ws")
                .newBuilder()
                .username("user")
                .password("s3cret")
                .build()

        AppMonitorWebSocketFactory(OkHttpClient(), sink).newWebSocket(Request.Builder().url(url).build(), listener)

        assertTrue(opened.await(5, TimeUnit.SECONDS))
        assertEquals("http://██:██@${server.hostName}:${server.port}/ws", sink.started().url)
    }

    private fun closePayload(
        code: Int,
        reason: String,
    ) = Buffer().writeShort(code).writeUtf8(reason).readUtf8()

    /** Echoes text and answers a close with the same code and reason. */
    private class Echo : WebSocketListener() {
        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            webSocket.send(text)
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(code, reason)
        }
    }
}
