package appmonitor.sdk.ktor

import appmonitor.sdk.core.BreakpointOutcome
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointStage
import appmonitor.v1.RequestStarted
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.isSaved
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.takeFrom
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.cancel
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** An absolute URL, as a resume's `url` must be. */
private val ABSOLUTE = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

/**
 * ADR-0111 A.1, C.1: the body [request] goes out with once its breakpoint lets it go, [request] edited in place; an
 * abort or an edit Ktor refuses fails the call with an [IOException], its `RequestStarted` captured first.
 */
internal suspend fun CaptureSink.holdRequest(
    request: HttpRequestBuilder,
    content: OutgoingContent,
    id: String,
    masking: Masking,
    limit: Int,
    replayOf: String?,
): OutgoingContent {
    val stage = BreakpointStage.BREAKPOINT_STAGE_REQUEST
    val rule = breakpointFor(request.method.value, request.url.buildString(), stage) ?: return content
    val started = requestStarted(id, request, content, masking, content.capture(limit), replayOf = replayOf)
    val failure =
        try {
            when (val outcome = hold(BreakpointHit(rule_id = rule.id, stage = stage, request = started, timeout_ms = rule.timeout_ms))) {
                is BreakpointOutcome.Abort -> IOException(outcome.message)
                is BreakpointOutcome.Resume -> return edit(request, content, outcome)
            }
        } catch (e: CancellationException) {
            fail(started, e)
            throw e
        } catch (e: IOException) {
            e
        } catch (e: Exception) {
            IOException("Breakpoint edit refused: ${e.message}", e)
        }
    fail(started, failure)
    throw failure
}

private fun CaptureSink.fail(
    started: RequestStarted,
    error: Throwable,
) {
    emit("RequestStarted") { started }
    emit("RequestFailed") { requestFailed(started.request_id, error) }
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
        if (!ABSOLUTE.containsMatchIn(it)) throw IOException("Breakpoint edit refused: not a URL: $it")
        request.url.takeFrom(it)
    }
    var type = content.contentType
    if (outcome.changesHeaders) {
        val own = content.headers.names().mapTo(HashSet()) { it.lowercase() }
        val edited = outcome.headers(capturedHeaders(request, content))
        type = edited.firstOrNull { it.first.equals(HttpHeaders.ContentType, ignoreCase = true) }?.let { ContentType.parse(it.second) }
        request.headers.clear()
        for ((name, value) in edited) {
            val lower = name.lowercase()
            if (lower == "content-type" || lower == "content-length" || lower in own) continue
            request.headers.append(name, value)
        }
    }
    val bytes =
        changes.body?.toByteArray()
            ?: (content as? OutgoingContent.ByteArrayContent)?.takeIf { type != content.contentType }?.bytes()
            ?: return content
    return ByteArrayContent(bytes, type)
}

/**
 * ADR-0111 A.1, C.1: the call the app reads once its breakpoint lets the response go, with its captured body;
 * [body] is what was captured of [call]'s. An abort fails the call with an [IOException].
 */
@OptIn(InternalAPI::class) // DEBT-0083
internal suspend fun CaptureSink.holdResponse(
    client: HttpClient,
    request: HttpRequestBuilder,
    content: OutgoingContent,
    call: HttpClientCall,
    body: CapturedBody,
    id: String,
    masking: Masking,
    limit: Int,
): Pair<HttpClientCall, CapturedBody> {
    val stage = BreakpointStage.BREAKPOINT_STAGE_RESPONSE
    val response = call.response
    if (response.status == HttpStatusCode.SwitchingProtocols) return call to body
    val rule = breakpointFor(request.method.value, request.url.buildString(), stage) ?: return call to body
    val hit =
        BreakpointHit(
            rule_id = rule.id,
            stage = stage,
            request = requestStarted(id, request, content, masking, content.capture(limit)),
            response = responseReceived(id, response, masking, body),
            timeout_ms = rule.timeout_ms,
        )
    val outcome =
        try {
            hold(hit)
        } catch (e: CancellationException) {
            emit("RequestFailed") { requestFailed(id, e) }
            throw e
        }
    if (outcome is BreakpointOutcome.Abort) {
        response.cancel()
        val failure = IOException(outcome.message)
        emit("RequestFailed") { requestFailed(id, failure) }
        throw failure
    }
    val resume = outcome as BreakpointOutcome.Resume
    val changes = resume.changes ?: return call to body
    if (changes.status_code == null && changes.body == null && !resume.changesHeaders) return call to body
    val replaced = changes.body
    val channel =
        when {
            replaced != null -> ByteReadChannel(replaced.toByteArray())
            response.isSaved -> ByteReadChannel(response.readRawBytes())
            else -> response.rawContent
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
    val captured = replaced?.let { CapturedBody(it, truncated = false, size = it.size.toLong()) } ?: body
    return edited to captured
}
