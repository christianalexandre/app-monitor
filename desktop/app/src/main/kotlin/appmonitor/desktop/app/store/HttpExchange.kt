package appmonitor.desktop.app.store

import appmonitor.desktop.storage.ExchangeDetail
import appmonitor.desktop.storage.StoredFrame
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Header
import appmonitor.v1.WebSocketFrame
import okio.ByteString

/** A body as the device sent it: [bytes] may hold fewer than [originalSize] bytes (ADR-0020). */
internal data class CapturedBody(
    val bytes: ByteString = ByteString.EMPTY,
    val truncated: Boolean = false,
    val originalSize: Long? = null,
)

/** One WebSocket frame, in the order the device sent it. */
internal data class CapturedFrame(
    val direction: WebSocketFrame.Direction,
    val opcode: WebSocketFrame.Opcode,
    val payload: CapturedBody,
    val atMs: Long,
)

/** One stored HTTP request and its outcome, as the detail pane shows it; [frames] holds the frames read so far. */
internal data class HttpExchange(
    val id: Long,
    val requestId: String,
    val method: String? = null,
    val url: String? = null,
    val requestHeaders: List<Header> = emptyList(),
    val requestBody: CapturedBody = CapturedBody(),
    val startedAtMs: Long? = null,
    val statusCode: Int? = null,
    val responseHeaders: List<Header> = emptyList(),
    val responseBody: CapturedBody = CapturedBody(),
    val error: String? = null,
    val endedAtMs: Long? = null,
    val frames: List<CapturedFrame> = emptyList(),
    val frameCount: Long = 0,
    val captureLayer: CaptureLayer = CaptureLayer.CAPTURE_LAYER_UNSPECIFIED,
) {
    val durationMs: Long? get() = durationOf(startedAtMs, endedAtMs)
}

/** Null unless both times are known. */
internal fun durationOf(
    startedAtMs: Long?,
    endedAtMs: Long?,
): Long? = if (startedAtMs != null && endedAtMs != null) endedAtMs - startedAtMs else null

internal fun HttpExchange(
    detail: ExchangeDetail,
    frames: List<StoredFrame>,
): HttpExchange {
    val row = detail.exchange
    val request = detail.request
    val response = detail.response
    return HttpExchange(
        id = row.id,
        requestId = row.requestId,
        method = row.method,
        url = row.url,
        requestHeaders = request?.headers.orEmpty(),
        requestBody = request?.let { CapturedBody(it.body, it.body_truncated, it.body_size) } ?: CapturedBody(),
        startedAtMs = row.startedAtMs,
        statusCode = row.statusCode,
        responseHeaders = response?.headers.orEmpty(),
        responseBody = response?.let { CapturedBody(it.body, it.body_truncated, it.body_size) } ?: CapturedBody(),
        error = row.error,
        endedAtMs = row.endedAtMs,
        frames =
            frames.map {
                CapturedFrame(
                    it.direction,
                    it.opcode,
                    CapturedBody(it.payload, it.payloadTruncated, it.payloadSize),
                    it.atMs,
                )
            },
        frameCount = row.frameCount,
        captureLayer = row.captureLayer,
    )
}
