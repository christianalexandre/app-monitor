package appmonitor.desktop.domain

import appmonitor.v1.CaptureLayer
import appmonitor.v1.Header
import appmonitor.v1.Hello
import appmonitor.v1.WebSocketFrame
import okio.ByteString

/** One run: the sessions between two "New run" (SDD 09). [name] is null until the developer names it. */
data class Run(
    val id: Long,
    val name: String?,
    val startedAtMs: Long,
    val endedAtMs: Long?,
)

/** One connection of an app, with the `Hello` it opened with. */
data class Session(
    val id: Long,
    val runId: Long,
    val serverSessionId: String,
    val hello: Hello,
    val peerAddress: String?,
    val connectedAtMs: Long,
    val disconnectedAtMs: Long?,
    val lastEnvelopeAtMs: Long?,
)

/** One request as lists and filters show it; no body, no headers. */
data class Exchange(
    val id: Long,
    val sessionId: Long,
    val requestId: String,
    val method: String?,
    val url: String?,
    val host: String?,
    val statusCode: Int?,
    val failed: Boolean,
    val startedAtMs: Long?,
    val endedAtMs: Long?,
    val captureLayer: CaptureLayer,
    val error: String?,
    val frameCount: Long,
    val grpcStatus: Int? = null,
    /** The rule that answered on the device instead of the network (ADR-0110). */
    val mockRuleId: Long? = null,
    /** The request id, in the same session, of the request this one sent again (ADR-0111). */
    val replayOf: String? = null,
) {
    /** A socket the connection layer saw (ADR-0103): no status, no headers, no body. */
    val isConnection: Boolean get() = captureLayer == CaptureLayer.CAPTURE_LAYER_CONNECTION

    /** Failed, status 400 and up, or a `grpc-status` other than OK (ADR-0107). */
    val isError: Boolean get() = failed || (statusCode ?: 0) >= 400 || (grpcStatus ?: 0) != 0
}

/** What the exchange list is narrowed to (SDD 09); every null or false field matches all. */
data class ExchangeFilter(
    val text: String? = null,
    val method: String? = null,
    val statusClass: StatusClass? = null,
    val failedOnly: Boolean = false,
    val deviceId: String? = null,
    val appId: String? = null,
    val runId: Long? = null,
    val sessionId: Long? = null,
)

/** A status code's hundreds: [from] to [from] + 99. */
enum class StatusClass(
    val from: Int,
) {
    INFORMATIONAL(100),
    SUCCESS(200),
    REDIRECTION(300),
    CLIENT_ERROR(400),
    SERVER_ERROR(500),
}

/** A body as the device sent it: [bytes] may hold fewer than [originalSize] bytes (ADR-0020). */
data class CapturedBody(
    val bytes: ByteString = ByteString.EMPTY,
    val truncated: Boolean = false,
    val originalSize: Long? = null,
)

/** One WebSocket frame, in the order the device sent it. */
data class CapturedFrame(
    val direction: WebSocketFrame.Direction,
    val opcode: WebSocketFrame.Opcode,
    val payload: CapturedBody,
    val atMs: Long,
)

/** What the app wrote to and read from one socket; each count null until it closed (ADR-0103). */
data class ConnectionBytes(
    val sent: Long?,
    val received: Long?,
)

/**
 * One stored HTTP request and its outcome, as the detail pane shows it; [frames] holds the frames read so far.
 * [connection] is set for a `CONNECTION` exchange only, which has no status, headers or body.
 */
data class HttpExchange(
    val id: Long,
    val requestId: String,
    val method: String? = null,
    val url: String? = null,
    val requestHeaders: List<Header> = emptyList(),
    val requestBody: CapturedBody = CapturedBody(),
    val startedAtMs: Long? = null,
    val statusCode: Int? = null,
    val grpcStatus: Int? = null,
    val responseHeaders: List<Header> = emptyList(),
    val responseBody: CapturedBody = CapturedBody(),
    val error: String? = null,
    val endedAtMs: Long? = null,
    val frames: List<CapturedFrame> = emptyList(),
    val frameCount: Long = 0,
    val captureLayer: CaptureLayer = CaptureLayer.CAPTURE_LAYER_UNSPECIFIED,
    val connection: ConnectionBytes? = null,
    val mockRuleId: Long? = null,
    val mockRuleName: String? = null,
    val replayOf: String? = null,
    val replayOfId: Long? = null,
) {
    val durationMs: Long? get() = durationOf(startedAtMs, endedAtMs)
}

/** Null unless both times are known. */
fun durationOf(
    startedAtMs: Long?,
    endedAtMs: Long?,
): Long? = if (startedAtMs != null && endedAtMs != null) endedAtMs - startedAtMs else null

/** Items lost to failed writes since the store opened; [lastError] is cleared by the next batch written. */
data class StoreHealth(
    val notSaved: Long = 0,
    val lastError: String? = null,
)
