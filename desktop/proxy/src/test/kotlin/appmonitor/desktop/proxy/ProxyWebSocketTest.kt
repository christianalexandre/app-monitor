package appmonitor.desktop.proxy

import appmonitor.desktop.storage.ExchangeDetail
import appmonitor.desktop.storage.StoredFrame
import appmonitor.v1.Envelope
import appmonitor.v1.RequestFailed
import appmonitor.v1.WebSocketFrame
import appmonitor.v1.WebSocketFrame.Direction
import appmonitor.v1.WebSocketFrame.Opcode
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProxyWebSocketTest {
    @Test
    fun aWebSocketThroughTheProxyShowsItsFramesBothWays() =
        withProxy {
            MockWebServer().use { origin ->
                origin.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Sec-WebSocket-Extensions", "permessage-deflate")
                        .webSocketUpgrade(Echo())
                        .build(),
                )
                origin.start()
                val big = "frame ".repeat(1_000)
                val closed = CountDownLatch(1)
                val listener =
                    object : WebSocketListener() {
                        private var echoes = 0

                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            webSocket.send("hello")
                            webSocket.send("010203".decodeHex())
                            webSocket.send(big)
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) = echoed(webSocket)

                        override fun onMessage(
                            webSocket: WebSocket,
                            bytes: ByteString,
                        ) = echoed(webSocket)

                        private fun echoed(webSocket: WebSocket) {
                            if (++echoes == 3) webSocket.close(1000, "bye")
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            closed.countDown()
                        }
                    }

                client(port).newWebSocket(Request.Builder().url(origin.url("/ws")).build(), listener)

                assertTrue(closed.await(10, TimeUnit.SECONDS), stored())
                val socket = ended(1).single()
                assertEquals(101, socket.exchange.statusCode)
                assertTrue(socket.response!!.headers.any { it.value_.contains("permessage-deflate") }, "deflate not negotiated")
                val frames = frames(socket, 8)
                val expected =
                    listOf(
                        Opcode.OPCODE_TEXT to "hello".encodeUtf8(),
                        Opcode.OPCODE_BINARY to "010203".decodeHex(),
                        Opcode.OPCODE_TEXT to big.encodeUtf8(),
                        Opcode.OPCODE_CLOSE to closePayload(1000, "bye"),
                    )
                for (direction in listOf(Direction.DIRECTION_SENT, Direction.DIRECTION_RECEIVED)) {
                    assertEquals(expected, frames.filter { it.direction == direction }.map { it.opcode to it.payload }, "$direction")
                }
                assertTrue(frames.all { it.payloadSize == it.payload.size.toLong() && !it.payloadTruncated })
                assertNull(storage.exchange(socket.exchange.id)!!.exchange.error)
            }
        }

    @Test
    fun aWebSocketCutWithoutACloseFrameEndsFailed() =
        withProxy {
            MockWebServer().use { origin ->
                origin.enqueue(MockResponse.Builder().webSocketUpgrade(Echo()).build())
                origin.start()
                val echoed = CountDownLatch(1)
                val listener =
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            webSocket.send("hello")
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            echoed.countDown()
                            webSocket.cancel()
                        }
                    }

                client(port).newWebSocket(Request.Builder().url(origin.url("/ws")).build(), listener)

                assertTrue(echoed.await(10, TimeUnit.SECONDS), stored())
                val socket = ended(1).single()
                withTimeout(10.seconds) {
                    while (storage.exchange(socket.exchange.id)!!.exchange.error == null) {
                        storage.flush()
                        delay(50)
                    }
                }
                assertEquals(WebSocketCapture.NO_CLOSE, storage.exchange(socket.exchange.id)!!.exchange.error)
                assertEquals(listOf(Direction.DIRECTION_SENT, Direction.DIRECTION_RECEIVED), frames(socket, 2).map { it.direction })
            }
        }

    @Test
    fun aMaskedFragmentedDeflatedMessageWithAPingBetweenIsReadByteByByte() {
        val (capture, events) = upgraded()
        val socket = WebSocketCapture.of(capture, response("permessage-deflate; client_no_context_takeover"))!!
        val text = "a message long enough to need sixteen bit lengths. ".repeat(10)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val first = deflated(deflater, text)
        val second = deflated(deflater, text)
        val wire =
            frame(fin = false, rsv1 = true, opcode = 1, first.copyOfRange(0, 10), mask = true) +
                frame(fin = true, rsv1 = false, opcode = 9, "ping".toByteArray(), mask = true) +
                frame(fin = true, rsv1 = false, opcode = 0, first.copyOfRange(10, first.size), mask = true) +
                frame(fin = true, rsv1 = true, opcode = 1, second, mask = true) +
                frame(fin = true, rsv1 = false, opcode = 2, ByteArray(300) { it.toByte() }, mask = true)

        wire.forEach { socket.client(Unpooled.wrappedBuffer(byteArrayOf(it))) }

        assertEquals(
            listOf(
                Opcode.OPCODE_PING to "ping".encodeUtf8(),
                Opcode.OPCODE_TEXT to text.encodeUtf8(),
                Opcode.OPCODE_TEXT to text.encodeUtf8(),
                Opcode.OPCODE_BINARY to ByteString.of(*ByteArray(300) { it.toByte() }),
            ),
            events.frames().map { it.opcode to it.payload },
        )
        assertTrue(events.frames().all { it.direction == Direction.DIRECTION_SENT })
    }

    @Test
    fun aMessageEndingInAFinalBlockLeavesLaterOnesReadableWithTheirWindow() {
        val text = "a message later ones copy from, under context takeover. ".repeat(10)
        val finished = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        finished.setInput(text.toByteArray())
        finished.finish()
        val buffer = ByteArray(4096)
        val final = buffer.copyOf(finished.deflate(buffer)) + 0
        val following = Deflater(Deflater.DEFAULT_COMPRESSION, true).apply { setDictionary(text.toByteArray()) }
        val copying = deflated(following, text)
        assertTrue(copying.size < 20, "the next message does not refer to the first")
        val wire =
            frame(fin = true, rsv1 = true, opcode = 1, final, mask = false) +
                frame(fin = true, rsv1 = true, opcode = 1, copying, mask = false) +
                frame(fin = true, rsv1 = true, opcode = 1, deflated(following, text), mask = false)

        for (chunks in listOf(wire.toList().map { byteArrayOf(it) }, listOf(wire))) {
            val (capture, events) = upgraded()
            val socket = WebSocketCapture.of(capture, response("permessage-deflate"))!!

            chunks.forEach { socket.origin(Unpooled.wrappedBuffer(it)) }

            assertEquals(listOf(text, text, text), events.frames().map { it.payload.utf8() })
        }
    }

    @Test
    fun anUnknownOpcodeStopsTheCaptureNotTheRest() {
        val (capture, events) = upgraded()
        val socket = WebSocketCapture.of(capture, response(null))!!

        socket.origin(Unpooled.wrappedBuffer(frame(fin = true, rsv1 = false, opcode = 3, byteArrayOf(1), mask = false)))
        socket.origin(Unpooled.wrappedBuffer(frame(fin = true, rsv1 = false, opcode = 1, "lost".toByteArray(), mask = false)))
        socket.client(Unpooled.wrappedBuffer(frame(fin = true, rsv1 = false, opcode = 1, "kept".toByteArray(), mask = true)))
        socket.ended()
        socket.ended()

        assertEquals(listOf("kept"), events.frames().map { it.payload.utf8() })
        assertEquals(listOf(WebSocketCapture.NO_CLOSE), events.failures())
    }

    @Test
    fun aNonWebSocketUpgradeIsNotRead() {
        val (capture, _) = upgraded()
        val h2c = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS)
        h2c.headers().set("Upgrade", "h2c")

        assertNull(WebSocketCapture.of(capture, h2c))
    }

    private class Events {
        val all = mutableListOf<Envelope>()

        fun frames() = all.filter { it.type == "WebSocketFrame" }.map { WebSocketFrame.ADAPTER.decode(it.payload) }

        fun failures() = all.filter { it.type == "RequestFailed" }.map { RequestFailed.ADAPTER.decode(it.payload).error }
    }

    private fun upgraded(): Pair<Capture, Events> {
        val events = Events()
        val capture =
            Capture("GET", "http://example.test/ws", emptyList(), {
                events.all += it
                true
            }, { 0L })
        capture.responded(101, emptyList(), BodyCapture())
        return capture to events
    }

    private fun response(extensions: String?) =
        DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS).apply {
            headers().set("Upgrade", "websocket")
            extensions?.let { headers().set("Sec-WebSocket-Extensions", it) }
        }

    private class Echo : WebSocketListener() {
        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            webSocket.send(text)
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            webSocket.send(bytes)
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(code, reason)
        }
    }

    private companion object {
        fun client(port: Int): OkHttpClient =
            OkHttpClient
                .Builder()
                .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                .build()

        suspend fun Proxy.frames(
            socket: ExchangeDetail,
            count: Int,
        ): List<StoredFrame> =
            withTimeout(10.seconds) {
                while (true) {
                    storage.flush()
                    val frames = storage.frames(socket.exchange.id)
                    if (frames.size >= count) return@withTimeout frames
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }

        fun closePayload(
            code: Int,
            reason: String,
        ): ByteString = Buffer().writeShort(code).writeUtf8(reason).readByteString()

        /** One message as permessage-deflate sends it: a sync flush, its last four bytes off. */
        fun deflated(
            deflater: Deflater,
            text: String,
        ): ByteArray {
            deflater.setInput(text.toByteArray())
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
                out.write(buffer, 0, count)
                if (count < buffer.size) break
            }
            val bytes = out.toByteArray()
            return bytes.copyOfRange(0, bytes.size - 4)
        }

        fun frame(
            fin: Boolean,
            rsv1: Boolean,
            opcode: Int,
            payload: ByteArray,
            mask: Boolean,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            out.write((if (fin) 0x80 else 0) or (if (rsv1) 0x40 else 0) or opcode)
            val maskBit = if (mask) 0x80 else 0
            when {
                payload.size < 126 -> {
                    out.write(maskBit or payload.size)
                }

                else -> {
                    out.write(maskBit or 126)
                    out.write(payload.size shr 8)
                    out.write(payload.size and 0xff)
                }
            }
            val key = byteArrayOf(0x12, 0x34, 0x56, 0x78)
            if (mask) out.write(key)
            out.write(if (mask) ByteArray(payload.size) { (payload[it].toInt() xor key[it % 4].toInt()).toByte() } else payload)
            return out.toByteArray()
        }
    }
}
