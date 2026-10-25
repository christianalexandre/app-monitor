package appmonitor.sdk.okhttp

import appmonitor.protocol.Protocol
import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.sdk.core.MonitorBridge
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.MockRule
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import com.squareup.wire.Message
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.Sink
import okio.Timeout
import okio.buffer
import java.net.URLDecoder
import java.util.TreeSet
import java.util.UUID

/** Where captured events go: the running monitor, or a test double. */
internal interface CaptureSink {
    /** Null when nothing should be captured. */
    val options: AppMonitorOptions?

    fun send(envelope: Envelope)

    /** The desktop's rule answering this call (ADR-0110 B.1), or null. */
    fun mockFor(
        method: String,
        url: String,
    ): MockRule? = null
}

internal object MonitorSink : CaptureSink {
    override val options: AppMonitorOptions? get() = MonitorBridge.options

    override fun send(envelope: Envelope) = MonitorBridge.send(envelope)

    override fun mockFor(
        method: String,
        url: String,
    ): MockRule? = MonitorBridge.mockFor(method, url)
}

/** The value a masked header, query or fragment parameter, URL user name or password is sent with (ADR-0018). */
internal const val MASK: String = "██"

/** [MASK] as `HttpUrl` percent-encodes it; turned back into [MASK] in a masked URL. */
private val ENCODED_MASK: String =
    MASK
        .encodeUtf8()
        .hex()
        .uppercase()
        .chunked(2)
        .joinToString("") { "%$it" }

internal val DEFAULT_MASKED_HEADERS: Set<String> = setOf("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie")

/** Response headers whose value is a URL, masked as one (ADR-0033). */
internal val URL_RESPONSE_HEADERS: Set<String> = setOf("Location", "Content-Location")

/** An RFC 3986 scheme at the start of a URI reference. */
private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/** ADR-0018 B: names compared case-insensitively, the name kept and the value replaced by [MASK]. */
internal class Masking(
    options: AppMonitorOptions,
) {
    private val enabled = options.masking // ADR-0034
    private val headerNames = caseInsensitive(DEFAULT_MASKED_HEADERS + options.maskedHeaders)
    private val queryNames = caseInsensitive(options.maskedQueryParameters)
    private val urlHeaderNames = caseInsensitive(URL_RESPONSE_HEADERS)

    /** With [base], a [URL_RESPONSE_HEADERS] value is resolved against it and masked by [url], or by [uri]. */
    fun headers(
        headers: Headers,
        base: HttpUrl? = null,
    ): List<Header> =
        headers.map { (name, value) ->
            val masked =
                when {
                    !enabled -> value
                    name in headerNames -> MASK
                    base != null && name in urlHeaderNames -> base.resolve(value)?.let(::url) ?: uri(value)
                    else -> value
                }
            Header(name, masked)
        }

    fun url(url: HttpUrl): String {
        if (!enabled) return url.toString()
        val maskQuery = (0 until url.querySize).any { url.queryParameterName(it) in queryNames }
        val fragment = url.encodedFragment?.let { fields(it, maskBare = false) }
        if (url.username.isEmpty() && url.password.isEmpty() && !maskQuery && fragment == url.encodedFragment) return url.toString()
        val masked = url.newBuilder().encodedFragment(fragment)
        if (url.username.isNotEmpty()) masked.username(MASK)
        if (url.password.isNotEmpty()) masked.password(MASK)
        if (maskQuery) {
            masked.query(null)
            for (i in 0 until url.querySize) {
                val name = url.queryParameterName(i)
                masked.addQueryParameter(name, if (name in queryNames) MASK else url.queryParameterValue(i))
            }
        }
        return masked.build().toString().replace(ENCODED_MASK, MASK)
    }

    /** ADR-0044: a value `HttpUrl` does not parse gets the URL rule by RFC 3986, as written, scheme or not. */
    private fun uri(value: String): String {
        val fragmentAt = value.indexOf('#').takeIf { it >= 0 } ?: value.length
        val queryAt = value.indexOf('?').takeIf { it in 0 until fragmentAt } ?: fragmentAt
        val query = if (queryAt < fragmentAt) "?" + fields(value.substring(queryAt + 1, fragmentAt), maskBare = true) else ""
        val fragment = if (fragmentAt < value.length) "#" + fields(value.substring(fragmentAt + 1), maskBare = false) else ""
        return userInfo(value.substring(0, queryAt)) + query + fragment
    }

    /** `scheme://user:password@…` or `//user:password@…` with a non-empty user name and password replaced by [MASK]. */
    private fun userInfo(hierarchy: String): String {
        val start = SCHEME.find(hierarchy)?.value?.length ?: 0
        if (!hierarchy.startsWith("//", start)) return hierarchy
        val end = hierarchy.indexOf('/', start + 2).takeIf { it >= 0 } ?: hierarchy.length
        val at = hierarchy.lastIndexOf('@', end - 1).takeIf { it >= start + 2 } ?: return hierarchy
        val masked =
            hierarchy.substring(start + 2, at).split(':', limit = 2).joinToString(":") { if (it.isEmpty()) it else MASK }
        return hierarchy.substring(0, start + 2) + masked + hierarchy.substring(at)
    }

    /**
     * ADR-0033: each `name=value` field whose name is masked becomes `name=`[MASK]; a field without `=`
     * too when [maskBare], as the rebuilt query of [url] does; other fields stay as written.
     */
    private fun fields(
        encoded: String,
        maskBare: Boolean,
    ): String {
        if (queryNames.isEmpty()) return encoded
        return encoded.split('&').joinToString("&") { field ->
            val name = field.substringBefore('=')
            if ((maskBare || '=' in field) && formDecoded(name) in queryNames) "$name=$MASK" else field
        }
    }

    private fun formDecoded(name: String): String =
        try {
            URLDecoder.decode(name, "UTF-8")
        } catch (e: IllegalArgumentException) {
            name
        }

    private fun caseInsensitive(names: Set<String>) = TreeSet(String.CASE_INSENSITIVE_ORDER).apply { addAll(names) }
}

/** A body as sent on the wire (ADR-0020): [size] null when unknown. */
internal class CapturedBody(
    val bytes: ByteString,
    val truncated: Boolean,
    val size: Long?,
) {
    companion object {
        val NONE = CapturedBody(ByteString.EMPTY, truncated = false, size = null)
        val EMPTY = CapturedBody(ByteString.EMPTY, truncated = false, size = 0)
    }
}

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

internal fun newRequestId(): String = UUID.randomUUID().toString()

internal fun requestStarted(
    id: String,
    request: Request,
    masking: Masking,
    body: CapturedBody,
    mockRuleId: Long? = null,
): RequestStarted {
    var headers = request.headers
    val contentType = request.body?.contentType()
    if (contentType != null && headers["Content-Type"] == null) {
        headers = headers.newBuilder().add("Content-Type", contentType.toString()).build()
    }
    return RequestStarted(
        request_id = id,
        method = request.method,
        url = masking.url(request.url),
        headers = masking.headers(headers),
        body = body.bytes,
        body_truncated = body.truncated,
        body_size = body.size,
        capture_layer = CaptureLayer.CAPTURE_LAYER_ADAPTER,
        mock_rule_id = mockRuleId,
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
        headers = masking.headers(response.headers, base = response.request.url),
        body = body.bytes,
        body_truncated = body.truncated,
        body_size = body.size,
    )

internal fun requestFailed(
    id: String,
    error: Throwable,
): RequestFailed = RequestFailed(request_id = id, error = error.toString())

/** Wraps [message] for the `network` channel as [type]; capture never throws into the app's call. */
internal fun CaptureSink.emit(
    type: String,
    message: () -> Message<*, *>,
) {
    val envelope =
        try {
            val event = message()
            Envelope(
                channel = Protocol.CHANNEL_NETWORK,
                type = type,
                payload = event.encodeByteString(),
                timestamp_ms = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            return
        }
    send(envelope)
}
