package appmonitor.sdk.ktor

import appmonitor.sdk.core.CapturedBody
import appmonitor.sdk.core.Masking
import appmonitor.v1.CaptureLayer
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import io.ktor.client.plugins.isSaved
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentLength
import io.ktor.util.StringValues
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.discard
import io.ktor.utils.io.readBuffer
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameType
import kotlinx.io.readByteArray
import okio.ByteString
import okio.ByteString.Companion.toByteString

private fun ByteArray.captured(limit: Int) =
    CapturedBody(toByteString(0, minOf(size, limit)), truncated = size > limit, size = size.toLong())

/** At most [limit] bytes of a body held in memory; a stream is never read, so the app's send is untouched (ADR-0063). */
internal fun OutgoingContent.capture(limit: Int): CapturedBody =
    when (this) {
        is OutgoingContent.ContentWrapper -> delegate().capture(limit)
        is OutgoingContent.NoContent, is OutgoingContent.ProtocolUpgrade -> CapturedBody.NONE
        is OutgoingContent.ByteArrayContent -> bytes().captured(limit)
        else -> CapturedBody(ByteString.EMPTY, truncated = true, size = contentLength)
    }

/** The body Ktor saved for the app, cut at [limit]; a streamed response is not read (ADR-0069). */
@OptIn(InternalAPI::class) // DEBT-0083
internal suspend fun HttpResponse.capture(limit: Int): CapturedBody =
    when {
        status == HttpStatusCode.SwitchingProtocols -> CapturedBody.NONE
        !isSaved -> unread()
        else -> rawContent.captured(limit)
    }

/** At most [limit] bytes of [this], the rest counted and not kept; throws the channel's close cause. */
private suspend fun ByteReadChannel.captured(limit: Int): CapturedBody {
    val head = readBuffer(limit.toLong()).readByteArray()
    val rest = discard()
    closedCause?.let { throw it }
    return CapturedBody(head.toByteString(), truncated = rest > 0, size = head.size + rest)
}

/** A response body sent empty, truncated, with its `Content-Length` when known. */
internal fun HttpResponse.unread(): CapturedBody = CapturedBody(ByteString.EMPTY, truncated = true, size = contentLength())

internal fun StringValues.pairs(): List<Pair<String, String>> = entries().flatMap { (name, values) -> values.map { name to it } }

internal fun requestStarted(
    id: String,
    request: HttpRequestBuilder,
    content: OutgoingContent,
    masking: Masking,
    body: CapturedBody,
    mockRuleId: Long? = null,
    replayOf: String? = null,
): RequestStarted {
    val withType = capturedHeaders(request, content)
    return RequestStarted(
        request_id = id,
        method = request.method.value,
        url = masking.url(request.url.buildString()),
        headers = masking.headers(withType),
        body = body.bytes,
        body_truncated = body.truncated,
        body_size = body.size,
        capture_layer = CaptureLayer.CAPTURE_LAYER_ADAPTER,
        mock_rule_id = mockRuleId,
        replay_of = replayOf,
    )
}

/** The headers `RequestStarted` shows for [request]: its own, [content]'s, and its content type. */
internal fun capturedHeaders(
    request: HttpRequestBuilder,
    content: OutgoingContent,
): List<Pair<String, String>> {
    val headers = request.headers.build().pairs() + content.headers.pairs()
    val contentType = content.contentType
    return if (contentType != null && headers.none { it.first.equals(HttpHeaders.ContentType, ignoreCase = true) }) {
        headers + (HttpHeaders.ContentType to contentType.toString())
    } else {
        headers
    }
}

internal fun responseReceived(
    id: String,
    response: HttpResponse,
    masking: Masking,
    body: CapturedBody,
): ResponseReceived =
    ResponseReceived(
        request_id = id,
        status_code = response.status.value,
        headers =
            masking.headers(
                response.headers.pairs(),
                base =
                    response.call.request.url
                        .toString(),
            ),
        body = body.bytes,
        body_truncated = body.truncated,
        body_size = body.size,
    )

/** [frame] as sent on the wire, payload cut at [limit] (ADR-0028); a close payload is its code and reason. */
internal fun webSocketFrame(
    id: String,
    direction: WebSocketFrame.Direction,
    frame: Frame,
    limit: Int,
): WebSocketFrame {
    val payload = frame.data.captured(limit)
    val opcode =
        when (frame.frameType) {
            FrameType.TEXT -> WebSocketFrame.Opcode.OPCODE_TEXT
            FrameType.BINARY -> WebSocketFrame.Opcode.OPCODE_BINARY
            FrameType.CLOSE -> WebSocketFrame.Opcode.OPCODE_CLOSE
            FrameType.PING -> WebSocketFrame.Opcode.OPCODE_PING
            FrameType.PONG -> WebSocketFrame.Opcode.OPCODE_PONG
        }
    return WebSocketFrame(
        request_id = id,
        direction = direction,
        opcode = opcode,
        payload = payload.bytes,
        payload_truncated = payload.truncated,
        payload_size = payload.size,
    )
}
