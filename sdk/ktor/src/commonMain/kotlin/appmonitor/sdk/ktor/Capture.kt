package appmonitor.sdk.ktor

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
import appmonitor.v1.WebSocketFrame
import com.squareup.wire.Message
import io.ktor.client.plugins.isSaved
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentLength
import io.ktor.http.decodeURLQueryComponent
import io.ktor.util.StringValues
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameType
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.time.Clock
import kotlin.uuid.Uuid

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

    /** Keeps [replay] for the desktop's `ReplayRequest` of [requestId] (ADR-0111 D.1); null once sent, else why not. */
    fun keepForReplay(
        requestId: String,
        replay: () -> String?,
    ) = Unit
}

internal object MonitorSink : CaptureSink {
    override val options: AppMonitorOptions? get() = MonitorBridge.options

    override fun send(envelope: Envelope) = MonitorBridge.send(envelope)

    override fun mockFor(
        method: String,
        url: String,
    ): MockRule? = MonitorBridge.mockFor(method, url)

    override fun keepForReplay(
        requestId: String,
        replay: () -> String?,
    ) = MonitorBridge.keepForReplay(requestId, replay)
}

/** The value a masked header, query or fragment parameter, URL user name or password is sent with (ADR-0018). */
internal const val MASK: String = "██"

internal val DEFAULT_MASKED_HEADERS: Set<String> = setOf("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie")

/** Response headers whose value is a URL, masked as one (ADR-0033). */
internal val URL_RESPONSE_HEADERS: Set<String> = setOf("Location", "Content-Location")

/** An RFC 3986 scheme at the start of a URI reference. */
private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/** RFC 3986 appendix B: scheme, authority, path, query and fragment of any URI reference. */
private val REFERENCE = Regex("^(?:([^:/?#]+):)?(?://([^/?#]*))?([^?#]*)(?:\\?([^#]*))?(?:#(.*))?$")

/** A `[user-info@]host[:port]` HttpUrl accepts: a host, and a port that is a number in range or empty. */
private val AUTHORITY = Regex("^(?:.*@)?(\\[[^]]*]|[^:@]+)(?::(\\d*))?$")

/** ADR-0018 B, ADR-0069: the rule of `sdk/android-okhttp`, by RFC 3986 on strings, the same on every target. */
internal class Masking(
    options: AppMonitorOptions,
) {
    private val enabled = options.masking // ADR-0034
    private val headerNames = lowercase(DEFAULT_MASKED_HEADERS + options.maskedHeaders)
    private val queryNames = lowercase(options.maskedQueryParameters)
    private val urlHeaderNames = lowercase(URL_RESPONSE_HEADERS)

    /** With [base], a [URL_RESPONSE_HEADERS] value is resolved against it when relative, then masked as a URL. */
    fun headers(
        headers: List<Pair<String, String>>,
        base: String? = null,
    ): List<Header> =
        headers.map { (name, value) ->
            val masked =
                when {
                    !enabled -> value
                    name.lowercase() in headerNames -> MASK
                    base != null && name.lowercase() in urlHeaderNames -> uri(resolve(base, value) ?: value)
                    else -> value
                }
            Header(name, masked)
        }

    fun url(url: String): String = if (enabled) uri(url) else url

    /** ADR-0044: user info, query and fragment of [value] masked as written; everything else unchanged. */
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
     * too when [maskBare], as in a query; other fields stay as written.
     */
    private fun fields(
        encoded: String,
        maskBare: Boolean,
    ): String {
        if (queryNames.isEmpty()) return encoded
        return encoded.split('&').joinToString("&") { field ->
            val name = field.substringBefore('=')
            if ((maskBare || '=' in field) && formDecoded(name).lowercase() in queryNames) "$name=$MASK" else field
        }
    }

    private fun formDecoded(name: String): String =
        try {
            name.decodeURLQueryComponent(plusIsSpace = true)
        } catch (e: Exception) {
            name
        }

    private fun lowercase(names: Set<String>) = names.mapTo(HashSet()) { it.lowercase() }
}

/**
 * RFC 3986 5.2 for a [reference] without a scheme against the absolute [base]; null when it has a
 * scheme, or an authority with no host or a port that is not a number in range.
 */
internal fun resolve(
    base: String,
    reference: String,
): String? {
    if (SCHEME.containsMatchIn(reference)) return null
    val b = REFERENCE.matchEntire(base) ?: return null
    val r = REFERENCE.matchEntire(reference) ?: return null
    val bAuthority = b.groupValues[2]
    val bPath = b.groupValues[3]
    val authority = r.groups[2]?.value
    val path = r.groupValues[3]
    val query = r.groups[4]?.value
    if (authority != null) {
        val port = AUTHORITY.matchEntire(authority)?.groupValues?.get(2) ?: return null
        if (port.isNotEmpty() && port.toIntOrNull()?.takeIf { it in 1..65535 } == null) return null
    }
    val resolved = StringBuilder(b.groupValues[1]).append(':')
    val (targetAuthority, targetPath, targetQuery) =
        when {
            authority != null -> {
                Triple(authority, removeDotSegments(path), query)
            }

            path.isEmpty() -> {
                Triple(bAuthority, bPath, query ?: b.groups[4]?.value)
            }

            path.startsWith("/") -> {
                Triple(bAuthority, removeDotSegments(path), query)
            }

            else -> {
                val merged = if (b.groups[2] != null && bPath.isEmpty()) "/$path" else bPath.substringBeforeLast('/', "") + "/" + path
                Triple(bAuthority, removeDotSegments(merged), query)
            }
        }
    if (b.groups[2] != null || authority != null) resolved.append("//").append(targetAuthority)
    resolved.append(targetPath)
    if (targetQuery != null) resolved.append('?').append(targetQuery)
    r.groups[5]?.let { resolved.append('#').append(it.value) }
    return resolved.toString()
}

/** RFC 3986 5.2.4. */
private fun removeDotSegments(path: String): String {
    val output = ArrayList<String>()
    val segments = path.split('/')
    for ((i, segment) in segments.withIndex()) {
        val last = i == segments.lastIndex
        when (segment) {
            "." -> {
                if (last) output.add("")
            }

            ".." -> {
                if (output.size > 1 || (output.size == 1 && output[0].isNotEmpty())) output.removeAt(output.lastIndex)
                if (last) output.add("")
            }

            else -> {
                output.add(segment)
            }
        }
    }
    return output.joinToString("/")
}

/** A body as sent on the wire (ADR-0020): [size] null when unknown. */
internal class CapturedBody(
    val bytes: ByteString,
    val truncated: Boolean,
    val size: Long?,
) {
    companion object {
        val NONE = CapturedBody(ByteString.EMPTY, truncated = false, size = null)
    }
}

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
internal suspend fun HttpResponse.capture(limit: Int): CapturedBody =
    when {
        status == HttpStatusCode.SwitchingProtocols -> CapturedBody.NONE
        !isSaved -> unread()
        else -> readRawBytes().captured(limit)
    }

/** A response body sent empty, truncated, with its `Content-Length` when known. */
internal fun HttpResponse.unread(): CapturedBody = CapturedBody(ByteString.EMPTY, truncated = true, size = contentLength())

internal fun newRequestId(): String = Uuid.random().toString()

private fun StringValues.pairs(): List<Pair<String, String>> = entries().flatMap { (name, values) -> values.map { name to it } }

internal fun requestStarted(
    id: String,
    request: HttpRequestBuilder,
    content: OutgoingContent,
    masking: Masking,
    body: CapturedBody,
    mockRuleId: Long? = null,
    replayOf: String? = null,
): RequestStarted {
    val headers = request.headers.build().pairs() + content.headers.pairs()
    val contentType = content.contentType
    val withType =
        if (contentType != null && headers.none { it.first.equals(HttpHeaders.ContentType, ignoreCase = true) }) {
            headers + (HttpHeaders.ContentType to contentType.toString())
        } else {
            headers
        }
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

internal fun requestFailed(
    id: String,
    error: Throwable,
): RequestFailed = RequestFailed(request_id = id, error = error.toString())

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

/** Wraps [message] for the `network` channel as [type]; capture never throws into the app's call. */
internal fun CaptureSink.emit(
    type: String,
    message: () -> Message<*, *>,
) {
    val envelope =
        try {
            Envelope(
                channel = Protocol.CHANNEL_NETWORK,
                type = type,
                payload = message().encodeByteString(),
                timestamp_ms = Clock.System.now().toEpochMilliseconds(),
            )
        } catch (e: Exception) {
            return
        }
    send(envelope)
}
