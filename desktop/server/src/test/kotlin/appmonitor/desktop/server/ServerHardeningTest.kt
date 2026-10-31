package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.cio.webSocketRawSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ServerHardeningTest {
    private val client = HttpClient(CIO) { install(WebSockets) }
    private var server: AppMonitorServer? = null

    @AfterTest
    fun tearDown() {
        client.close()
        runBlocking { server?.stop() }
    }

    @Test
    fun `a body at the protocol limit is delivered`() =
        runBlocking {
            val port = start()
            val headers = List(50) { Header("X-Header-$it", "v".repeat(1_000)) }
            val full =
                envelope(
                    Protocol.CHANNEL_NETWORK,
                    "RequestStarted",
                    RequestStarted(
                        request_id = "req-1",
                        url = "https://example.com/" + "p".repeat(8_000),
                        headers = headers,
                        body = ByteArray(Protocol.MAX_BODY_BYTES).toByteString(),
                    ).encode(),
                )

            withTimeout(TIMEOUT) {
                val session = async { server!!.sessions.first() }
                connect(port) {
                    handshake()
                    send(Frame.Binary(true, full.encode()))
                    session.await().envelopes.first()
                }.let { assertEquals(full, it) }
            }
        }

    @Test
    fun `an oversized frame closes the connection with 1009`() =
        runBlocking {
            val port = start()

            // A bare socket announces the size and sends no payload: Ktor's client, still writing, sees 1006.
            val code =
                withContext(Dispatchers.IO) {
                    Socket(HOST, port).use { socket ->
                        socket.soTimeout = TIMEOUT.inWholeMilliseconds.toInt()
                        val output = DataOutputStream(socket.getOutputStream())
                        val input = DataInputStream(socket.getInputStream())
                        output.upgrade(input)
                        output.writeMaskedFrame(hello.encode())
                        input.readFrame()
                        output.write(byteArrayOf(0x82.toByte(), 0xFF.toByte()))
                        output.writeLong(Protocol.MAX_FRAME_BYTES + 1)
                        output.writeInt(0)
                        output.flush()
                        generateSequence { input.readFrame() }.first { it.first == 0x8 }.second.let {
                            DataInputStream(it.inputStream()).readUnsignedShort()
                        }
                    }
                }

            assertEquals(
                CloseReason.Codes.TOO_BIG.code
                    .toInt(),
                code,
            )
        }

    @Test
    fun `a client that stops answering pings loses its session within the bound`() =
        runBlocking {
            val port = start(pingPeriod = 100.milliseconds, pingTimeout = 200.milliseconds)
            val session = async { server!!.sessions.first() }
            // A raw session neither answers pings nor closes back: a device gone from the network.
            val raw = client.webSocketRawSession(host = HOST, port = port, path = Protocol.WEBSOCKET_PATH)
            try {
                raw.outgoing.send(Frame.Binary(true, hello.encode()))
                val connected = withTimeout(TIMEOUT) { session.await() }
                val started = TimeSource.Monotonic.markNow()

                withTimeout(TIMEOUT) { connected.envelopes.toList() }

                // Period + timeout, plus scheduling slack.
                val elapsed = started.elapsedNow()
                assert(elapsed < 1.seconds) { "Session ended after $elapsed" }
            } finally {
                raw.outgoing.close()
            }
        }

    @Test
    fun `a client that answers pings keeps its session`() =
        runBlocking {
            val port = start(pingPeriod = 100.milliseconds, pingTimeout = 200.milliseconds)
            val sent = requestStarted("req-1")

            withTimeout(TIMEOUT) {
                val session = async { server!!.sessions.first() }
                val received =
                    connect(port) {
                        handshake()
                        // Several ping rounds; the client session answers them.
                        delay(1_000)
                        send(Frame.Binary(true, sent.encode()))
                        session.await().envelopes.first()
                    }
                assertEquals(sent, received)
            }
        }

    @Test
    fun `an envelope on a channel not accepted closes with protocol error after what came before`() =
        assertRejected(envelope("logs", "LogLine", ByteArray(0)))

    @Test
    fun `a network event without request id closes with protocol error`() =
        assertRejected(envelope(Protocol.CHANNEL_NETWORK, "ResponseReceived", ResponseReceived(status_code = 200).encode()))

    @Test
    fun `an undecodable network event closes with protocol error`() =
        assertRejected(envelope(Protocol.CHANNEL_NETWORK, "RequestFailed", byteArrayOf(0x0A, 0x7F)))

    @Test
    fun `after its protocol error close the server reads until the client's close`() =
        runBlocking {
            val port = start()
            val session = async { server!!.sessions.first() }

            withContext(Dispatchers.IO) {
                Socket(HOST, port).use { socket ->
                    socket.soTimeout = TIMEOUT.inWholeMilliseconds.toInt()
                    val output = DataOutputStream(socket.getOutputStream())
                    val input = DataInputStream(socket.getInputStream())
                    output.upgrade(input)
                    output.writeMaskedFrame(hello.encode())
                    input.readFrame()
                    output.writeMaskedFrame(envelope("logs", "LogLine", ByteArray(0)).encode())
                    val code =
                        generateSequence { input.readFrame() }.first { it.first == 0x8 }.second.let {
                            DataInputStream(it.inputStream()).readUnsignedShort()
                        }
                    assertEquals(
                        CloseReason.Codes.PROTOCOL_ERROR.code
                            .toInt(),
                        code,
                    )
                    val envelopes = withTimeout(TIMEOUT) { session.await() }.envelopes
                    // Still reading: the session ends only once the client's close comes, or the bound passes.
                    assertNull(withTimeoutOrNull(300.milliseconds) { envelopes.toList() })
                    // An empty close, masked with a zero key.
                    output.write(byteArrayOf(0x88.toByte(), 0x80.toByte(), 0, 0, 0, 0))
                    output.flush()
                    assertEquals(emptyList(), withTimeout(500.milliseconds) { envelopes.toList() })
                }
            }
        }

    @Test
    fun `a network type the server does not know passes through`() =
        runBlocking {
            val port = start()
            val unknown = envelope(Protocol.CHANNEL_NETWORK, "GrpcCall", ByteArray(0))
            val failed = envelope(Protocol.CHANNEL_NETWORK, "RequestFailed", RequestFailed("req-1", "timeout").encode())

            val received =
                withTimeout(TIMEOUT) {
                    val session = async { server!!.sessions.first() }
                    connect(port) {
                        handshake()
                        send(Frame.Binary(true, unknown.encode()))
                        send(Frame.Binary(true, failed.encode()))
                        val envelopes = session.await().envelopes
                        listOf(envelopes.first(), envelopes.first())
                    }
                }

            assertEquals(listOf(unknown, failed), received)
        }

    private fun assertRejected(bad: Envelope) =
        runBlocking {
            val port = start()
            val good = requestStarted("req-1")

            withTimeout(TIMEOUT) {
                val session = async { server!!.sessions.first() }
                val reason =
                    connect(port) {
                        handshake()
                        send(Frame.Binary(true, good.encode()))
                        send(Frame.Binary(true, bad.encode()))
                        closeReason.await()
                    }

                assertEquals(CloseReason.Codes.PROTOCOL_ERROR.code, reason?.code)
                assertEquals(listOf(good), session.await().envelopes.toList())
            }
        }

    private suspend fun start(
        pingPeriod: Duration = 15.seconds,
        pingTimeout: Duration = 15.seconds,
    ): Int =
        AppMonitorServer(
            ServerConfig(host = HOST, port = 0, advertise = false),
            pingPeriod = pingPeriod,
            pingTimeout = pingTimeout,
        ) { _, _ -> error("not advertising") }.also { server = it }.start()

    private suspend fun <T> connect(
        port: Int,
        block: suspend DefaultClientWebSocketSession.() -> T,
    ): T {
        var result: Result<T>? = null
        client.webSocket(host = HOST, port = port, path = Protocol.WEBSOCKET_PATH) {
            result = runCatching { block() }
        }
        return result!!.getOrThrow()
    }

    private suspend fun DefaultClientWebSocketSession.handshake() {
        send(Frame.Binary(true, hello.encode()))
        incoming.receive()
    }

    private fun DataOutputStream.upgrade(input: DataInputStream) {
        writeBytes(
            "GET ${Protocol.WEBSOCKET_PATH} HTTP/1.1\r\nHost: $HOST\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n",
        )
        flush()
        var tail = 0
        while (tail != 0x0D0A0D0A) tail = (tail shl 8) or input.readUnsignedByte()
    }

    // Masked with a zero key, so the payload goes as is.
    private fun DataOutputStream.writeMaskedFrame(payload: ByteArray) {
        write(byteArrayOf(0x82.toByte(), (0x80 or 126).toByte()))
        writeShort(payload.size)
        writeInt(0)
        write(payload)
        flush()
    }

    private fun DataInputStream.readFrame(): Pair<Int, ByteArray> {
        val opcode = readUnsignedByte() and 0x0F
        val length =
            when (val short = readUnsignedByte() and 0x7F) {
                126 -> readUnsignedShort().toLong()
                127 -> readLong()
                else -> short.toLong()
            }
        return opcode to ByteArray(length.toInt()).also { readFully(it) }
    }

    private companion object {
        const val HOST = "127.0.0.1"
        val TIMEOUT = 10.seconds

        val hello =
            envelope(
                Protocol.CHANNEL_CORE,
                "Hello",
                Hello(
                    protocol_version = Protocol.VERSION,
                    platform = Platform.PLATFORM_JVM,
                    device_id = "device-0001",
                    channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK, "logs"),
                ).encode(),
            )

        fun requestStarted(id: String) =
            envelope(Protocol.CHANNEL_NETWORK, "RequestStarted", RequestStarted(request_id = id, method = "GET").encode())

        fun envelope(
            channel: String,
            type: String,
            payload: ByteArray,
        ) = Envelope(channel = channel, type = type, payload = payload.toByteString(), timestamp_ms = 1L)
    }
}
