package appmonitor.protocol

import appmonitor.v1.BreakpointAbort
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointResume
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointRules
import appmonitor.v1.BreakpointStage
import appmonitor.v1.CaptureLayer
import appmonitor.v1.DeliveryReport
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.Hello
import appmonitor.v1.HelloAck
import appmonitor.v1.MockFailure
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import appmonitor.v1.MockRules
import appmonitor.v1.Platform
import appmonitor.v1.ReplayRefused
import appmonitor.v1.ReplayRequest
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import com.squareup.wire.Message
import okio.ByteString
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
            sdk_name = "kotlin",
            app_name = "Sample",
        )

    val helloAck =
        HelloAck(
            protocol_version = Protocol.VERSION,
            session_id = "session-0001",
            channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK),
            desktop_id = "0b5d2f0e-7c1a-4f3e-9a51-2d6c8e4b7f10",
        )

    val deliveryReport =
        DeliveryReport(
            evicted = 3,
            oversized = 1,
            previous_session_id = "session-0000",
            previous_session_written = 42,
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
            capture_layer = CaptureLayer.CAPTURE_LAYER_ADAPTER,
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
            payload_size = 4,
        )

    // ADR-0103: one socket, opened at TIMESTAMP_MS and closed 1.5 s later.
    val connectionStarted =
        RequestStarted(
            request_id = "conn-0001",
            method = "CONNECT",
            url = "tcp://push.example.com:5223",
            capture_layer = CaptureLayer.CAPTURE_LAYER_CONNECTION,
        )

    val connectionClosed =
        ResponseReceived(
            request_id = "conn-0001",
            body_size = 2_048,
            sent_size = 512,
        )

    // ADR-0113: a response for any method, then a slow failure for one.
    val mockRules =
        MockRules(
            rules =
                listOf(
                    MockRule(
                        id = 7,
                        host = "api.example.com",
                        path = "/v1/items/*",
                        response =
                            MockResponse(
                                status_code = 503,
                                headers = listOf(Header("Retry-After", "30")),
                                body = """{"error":"maintenance"}""".encodeUtf8(),
                            ),
                    ),
                    MockRule(
                        id = 9,
                        method = "POST",
                        host = "api.example.com",
                        path = "/v1/orders",
                        delay_ms = 2_000,
                        failure = MockFailure(message = "connection reset"),
                    ),
                ),
        )

    // ADR-0118: a request and response breakpoint for any method, then a request one for a POST.
    val breakpointRules =
        BreakpointRules(
            rules =
                listOf(
                    BreakpointRule(
                        id = 3,
                        host = "api.example.com",
                        path = "/v1/items/*",
                        request = true,
                        response = true,
                        timeout_ms = 60_000,
                    ),
                    BreakpointRule(
                        id = 4,
                        method = "POST",
                        host = "api.example.com",
                        path = "/v1/orders",
                        request = true,
                        timeout_ms = 5_000,
                    ),
                ),
        )

    // A response held with its request, the token masked as the capture masks it.
    val breakpointHit =
        BreakpointHit(
            rule_id = 3,
            stage = BreakpointStage.BREAKPOINT_STAGE_RESPONSE,
            request =
                RequestStarted(
                    request_id = "req-0004",
                    method = "GET",
                    url = "https://api.example.com/v1/items/42",
                    headers = listOf(Header("Authorization", "██")),
                    body_size = 0,
                    capture_layer = CaptureLayer.CAPTURE_LAYER_ADAPTER,
                ),
            response =
                ResponseReceived(
                    request_id = "req-0004",
                    status_code = 200,
                    headers = listOf(Header("Content-Type", "application/json")),
                    body = """{"id":42}""".encodeUtf8(),
                    body_size = 9,
                ),
            timeout_ms = 60_000,
        )

    // Only what the developer changed: the status, one header set and one removed, the body.
    val breakpointResume =
        BreakpointResume(
            request_id = "req-0004",
            stage = BreakpointStage.BREAKPOINT_STAGE_RESPONSE,
            status_code = 404,
            set_headers = listOf(Header("Cache-Control", "no-store")),
            removed_headers = listOf("Content-Type"),
            body = ByteString.EMPTY,
        )

    val breakpointAbort =
        BreakpointAbort(
            request_id = "req-0005",
            stage = BreakpointStage.BREAKPOINT_STAGE_REQUEST,
            message = "aborted on the desktop",
        )

    // ADR-0120: the first request sent again, under a new id.
    val replayStarted = requestStarted.copy(request_id = "req-0006", replay_of = REQUEST_ID)

    val replayRequest = ReplayRequest(request_id = REQUEST_ID)

    val replayRefused = ReplayRefused(request_id = "req-0007", reason = "no longer kept by the SDK")

    /** Fixture file name to the envelope it holds. */
    val envelopes: Map<String, Envelope> =
        mapOf(
            "hello.bin" to envelope(Protocol.CHANNEL_CORE, "Hello", hello),
            "hello_ack.bin" to envelope(Protocol.CHANNEL_CORE, "HelloAck", helloAck),
            "delivery_report.bin" to envelope(Protocol.CHANNEL_CORE, "DeliveryReport", deliveryReport),
            "request_started.bin" to envelope(Protocol.CHANNEL_NETWORK, "RequestStarted", requestStarted),
            "response_received.bin" to envelope(Protocol.CHANNEL_NETWORK, "ResponseReceived", responseReceived),
            "request_failed.bin" to envelope(Protocol.CHANNEL_NETWORK, "RequestFailed", requestFailed),
            "websocket_frame.bin" to envelope(Protocol.CHANNEL_NETWORK, "WebSocketFrame", webSocketFrame),
            "connection_started.bin" to envelope(Protocol.CHANNEL_NETWORK, "RequestStarted", connectionStarted),
            "connection_closed.bin" to
                envelope(Protocol.CHANNEL_NETWORK, "ResponseReceived", connectionClosed, TIMESTAMP_MS + 1_500),
            "mock_rules.bin" to envelope(Protocol.CHANNEL_INTERACTION, "MockRules", mockRules),
            "breakpoint_rules.bin" to envelope(Protocol.CHANNEL_INTERACTION, "BreakpointRules", breakpointRules),
            "breakpoint_hit.bin" to envelope(Protocol.CHANNEL_INTERACTION, "BreakpointHit", breakpointHit),
            "breakpoint_resume.bin" to envelope(Protocol.CHANNEL_INTERACTION, "BreakpointResume", breakpointResume),
            "breakpoint_abort.bin" to envelope(Protocol.CHANNEL_INTERACTION, "BreakpointAbort", breakpointAbort),
            "replay_started.bin" to envelope(Protocol.CHANNEL_NETWORK, "RequestStarted", replayStarted),
            "replay_request.bin" to envelope(Protocol.CHANNEL_INTERACTION, "ReplayRequest", replayRequest),
            "replay_refused.bin" to envelope(Protocol.CHANNEL_INTERACTION, "ReplayRefused", replayRefused),
        )

    private fun envelope(
        channel: String,
        type: String,
        message: Message<*, *>,
        timestampMs: Long = TIMESTAMP_MS,
    ): Envelope = Envelope(channel = channel, type = type, payload = message.encodeByteString(), timestamp_ms = timestampMs)
}
