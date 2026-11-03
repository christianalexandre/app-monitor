package appmonitor.sdk.okhttp

import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.sdk.core.CapturedBody
import appmonitor.sdk.core.Masking
import appmonitor.v1.CaptureLayer
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.ByteString
import okio.Sink
import okio.Timeout
import okio.buffer

/** ADR-0181: the rule is `sdk/core`'s; a `Location` resolves as OkHttp follows it, backslashes included. */
internal fun okHttpMasking(options: AppMonitorOptions): Masking =
    Masking(options) { base, reference -> base.toHttpUrlOrNull()?.resolve(reference)?.toString() }

/** At most [limit] bytes of the body; one-shot and duplex bodies are never read (ADR-0018 C). */
internal fun RequestBody?.capture(limit: Int): CapturedBody {
    if (this == null) return CapturedBody.NONE
    val length = contentLength().takeIf { it >= 0 }
    if (isOneShot() || isDuplex()) return CapturedBody(ByteString.EMPTY, truncated = true, size = length)
    val sink = LimitedSink(limit)
    return try {
        sink.buffer().apply { writeTo(this) }.flush()
        CapturedBody(sink.captured.readByteString(), truncated = false, size = sink.total)
    } catch (e: LimitReached) {
        CapturedBody(sink.captured.readByteString(), truncated = true, size = length)
    } catch (e: Exception) {
        CapturedBody(ByteString.EMPTY, truncated = true, size = length)
    }
}

private class LimitReached : RuntimeException()

/** Keeps the first [limit] bytes; throws [LimitReached] past them so a large body is not written whole. */
private class LimitedSink(
    private val limit: Int,
) : Sink {
    val captured = Buffer()
    var total = 0L

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        total += byteCount
        val kept = minOf(byteCount, limit - captured.size)
        source.read(captured, kept)
        source.skip(byteCount - kept)
        if (total > limit) throw LimitReached()
    }

    override fun flush() = Unit

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() = Unit
}

internal fun requestStarted(
    id: String,
    request: Request,
    masking: Masking,
    body: CapturedBody,
    mockRuleId: Long? = null,
    replayOf: String? = null,
): RequestStarted {
    var headers = request.headers
    val contentType = request.body?.contentType()
    if (contentType != null && headers["Content-Type"] == null) {
        headers = headers.newBuilder().add("Content-Type", contentType.toString()).build()
    }
    return RequestStarted(
        request_id = id,
        method = request.method,
        url = masking.url(request.url.toString()),
        headers = masking.headers(headers.toList()),
        body = body.bytes,
        body_truncated = body.truncated,
        body_size = body.size,
        capture_layer = CaptureLayer.CAPTURE_LAYER_ADAPTER,
        mock_rule_id = mockRuleId,
        replay_of = replayOf,
    )
}

internal fun responseReceived(
    id: String,
    response: Response,
    masking: Masking,
    body: CapturedBody,
): ResponseReceived =
    ResponseReceived(
        request_id = id,
        status_code = response.code,
        headers = masking.headers(response.headers.toList(), base = response.request.url.toString()),
        body = body.bytes,
        body_truncated = body.truncated,
        body_size = body.size,
    )
