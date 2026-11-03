package appmonitor.sdk.ktor

import appmonitor.sdk.core.BreakpointOutcome
import appmonitor.sdk.core.CallCapture
import appmonitor.sdk.core.CapturedBody
import appmonitor.sdk.core.Masking
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.isSaved
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.takeFrom
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.cancel
import kotlinx.coroutines.cancel
import kotlinx.io.IOException

/** An absolute URL, as a resume's `url` must be. */
private val ABSOLUTE = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

/** The body [request] goes out with once its breakpoint lets it go, [request] edited in place. */
internal suspend fun CallCapture.holdRequest(
    request: HttpRequestBuilder,
    content: OutgoingContent,
    masking: Masking,
    limit: Int,
    replayOf: String?,
): OutgoingContent {
    val rule = requestBreakpoint(request.method.value, request.url.buildString()) ?: return content
    val started = requestStarted(id, request, content, masking, content.capture(limit), replayOf = replayOf)
    return holdRequest(rule, started, { sink.hold(it) }) { edit(request, content, it) }
}

/** [content] with [type] and [headers] in place of its own, its bytes and how they are sent left as they are. */
private class EditedContent(
    content: OutgoingContent,
    override val contentType: ContentType?,
    override val headers: Headers,
) : OutgoingContent.ContentWrapper(content) {
    override fun copy(delegate: OutgoingContent): ContentWrapper = EditedContent(delegate, contentType, headers)
}

/** [request] with the resume's method, URL and headers, and the body it goes out with. */
private fun edit(
    request: HttpRequestBuilder,
    content: OutgoingContent,
    outcome: BreakpointOutcome.Resume,
): OutgoingContent {
    val changes = outcome.changes ?: return content
    changes.method?.let { request.method = HttpMethod.parse(it) }
    changes.url?.let {
        if (!ABSOLUTE.containsMatchIn(it)) throw IOException("${CallCapture.EDIT_REFUSED}not a URL: $it")
        request.url.takeFrom(it)
    }
    var type = content.contentType
    var own = content.headers
    if (outcome.changesHeaders) {
        val carried = content.headers.names().mapTo(HashSet()) { it.lowercase() }
        val edited = outcome.headers(capturedHeaders(request, content))
        type = edited.firstOrNull { it.first.equals(HttpHeaders.ContentType, ignoreCase = true) }?.let { ContentType.parse(it.second) }
        val kept = HeadersBuilder()
        request.headers.clear()
        for ((name, value) in edited) {
            val lower = name.lowercase()
            when {
                lower == "content-type" || lower == "content-length" -> Unit
                lower in carried -> kept.append(name, value)
                else -> request.headers.append(name, value)
            }
        }
        own = kept.build()
    }
    val bytes = changes.body?.toByteArray()
    if (bytes == null && type == content.contentType && own == content.headers) return content
    // ByteArrayContent's own type is dropped: the wrapper answers for it, an edit to none included.
    return EditedContent(bytes?.let { ByteArrayContent(it) } ?: content, type, own)
}

/** The call the app reads once its breakpoint lets the response go, with its captured body; [body] is what was captured of [call]'s. */
@OptIn(InternalAPI::class) // DEBT-0083
internal suspend fun CallCapture.holdResponse(
    client: HttpClient,
    request: HttpRequestBuilder,
    content: OutgoingContent,
    call: HttpClientCall,
    body: CapturedBody,
    masking: Masking,
    limit: Int,
): Pair<HttpClientCall, CapturedBody> {
    val response = call.response
    val rule = responseBreakpoint(request.method.value, request.url.buildString(), response.status.value) ?: return call to body
    val resume =
        holdResponse(
            rule,
            requestStarted(id, request, content, masking, content.capture(limit)),
            responseReceived(id, response, masking, body),
            { sink.hold(it) },
            { response.cancel() },
        ) ?: return call to body
    val changes = checkNotNull(resume.changes)
    val replaced = changes.body
    val channel =
        when {
            replaced != null -> {
                // The original's engine connection goes back to its pool; the edited call shares its context, so only the channel.
                if (!response.isSaved) response.rawContent.cancel()
                ByteReadChannel(replaced.toByteArray())
            }

            response.isSaved -> {
                ByteReadChannel(response.readRawBytes())
            }

            else -> {
                response.rawContent
            }
        }
    val headers =
        Headers.build {
            for ((name, value) in resume.headers(response.headers.pairs())) append(name, value)
        }
    val edited =
        builtCall(
            client,
            request,
            content,
            changes.status_code?.let { HttpStatusCode.fromValue(it) } ?: response.status,
            headers,
            channel,
            response.version,
            response.requestTime,
        ) { response.coroutineContext }
    return edited to (replaced?.let { CapturedBody.whole(it) } ?: body)
}
