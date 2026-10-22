package appmonitor.protocol

import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.Hello
import appmonitor.v1.HelloAck
import appmonitor.v1.Platform
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import com.squareup.wire.Message
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

// Golden values behind protocol/fixtures: changing one changes the contract.
object Samples {
    private const val TIMESTAMP_MS = 1_790_000_000_000L
    private const val REQUEST_ID = "req-0001"

    val hello =
        Hello(
            protocol_version = Protocol.VERSION,
            platform = Platform.PLATFORM_ANDROID,
            device_id = "device-0001",
            device_name = "Pixel 9 (emulator)",
            os_version = "Android 16",
            app_id = "dev.example.sample",
            app_version = "1.0.0",
            sdk_version = "0.1.0",
            channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK),
        )

    val helloAck =
        HelloAck(
            protocol_version = Protocol.VERSION,
            session_id = "session-0001",
            channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK),
        )

    val requestStarted =
        RequestStarted(
            request_id = REQUEST_ID,
            method = "POST",
            url = "https://api.example.com/v1/items?page=2",
            headers =
                listOf(
                    Header("Content-Type", "application/json"),
                    Header("Accept", "application/json"),
                ),
            body = """{"name":"café"}""".encodeUtf8(),
            body_size = 16,
        )

    val responseReceived =
        ResponseReceived(
            request_id = REQUEST_ID,
            status_code = 201,
            headers = listOf(Header("Content-Type", "application/octet-stream")),
            body = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte()).toByteString(),
            body_truncated = true,
            body_size = 2_097_152,
        )

    val requestFailed =
        RequestFailed(
            request_id = "req-0002",
            error = "java.net.SocketTimeoutException: timeout",
        )

    val webSocketFrame =
        WebSocketFrame(
            request_id = "req-0003",
            direction = WebSocketFrame.Direction.DIRECTION_RECEIVED,
            opcode = WebSocketFrame.Opcode.OPCODE_TEXT,
            payload = "ping".encodeUtf8(),
        )

    /** Fixture file name to the envelope it holds. */
    val envelopes: Map<String, Envelope> =
        mapOf(
            "hello.bin" to envelope(Protocol.CHANNEL_CORE, "Hello", hello),
            "hello_ack.bin" to envelope(Protocol.CHANNEL_CORE, "HelloAck", helloAck),
            "request_started.bin" to envelope(Protocol.CHANNEL_NETWORK, "RequestStarted", requestStarted),
            "response_received.bin" to envelope(Protocol.CHANNEL_NETWORK, "ResponseReceived", responseReceived),
            "request_failed.bin" to envelope(Protocol.CHANNEL_NETWORK, "RequestFailed", requestFailed),
            "websocket_frame.bin" to envelope(Protocol.CHANNEL_NETWORK, "WebSocketFrame", webSocketFrame),
        )

    private fun envelope(
        channel: String,
        type: String,
        message: Message<*, *>,
    ): Envelope = Envelope(channel = channel, type = type, payload = message.encodeByteString(), timestamp_ms = TIMESTAMP_MS)
}
