package appmonitor.sdk.okhttp

import appmonitor.sdk.core.BreakpointOutcome
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointStage
import appmonitor.v1.MockRule
import appmonitor.v1.RequestStarted
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.blackholeSink
import okio.buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends each call's request, response and failure to the running `AppMonitor` (ADR-0017, ADR-0018), answers a
 * call the desktop's mock rule matches (ADR-0110), holds one at its breakpoint, and sends a call again when the
 * desktop asks (ADR-0111). Add
 * it with `addInterceptor`, one event set per call; `addNetworkInterceptor` gives one per network hop, with
 * bodies still compressed as on the wire, no mock answer and no resume to another host or port (ADR-0128). It
 * captures nothing until `AppMonitor.start` runs.
 */
public class AppMonitorInterceptor : Interceptor {
    private val sink: CaptureSink

    // The replays in flight, by their cloned call, to the request id each sends again.
    private val replays = ConcurrentHashMap<Call, String>()

    // ADR-0026: beside an internal primary constructor, this one would be missing from the ABI dump.
    public constructor() {
        sink = MonitorSink
    }

    internal constructor(sink: CaptureSink) {
        this.sink = sink
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val options = sink.options
        if (options == null || original.tag(WebSocketCapture::class.java) != null) return chain.proceed(original)
        val id = newRequestId()
        val masking = Masking(options)
        val call = chain.call()
        val replayOf = replays[call]
        // ADR-0128: OkHttp throws when a network interceptor skips proceed() or changes the host or port.
        val network = chain.connection() != null
        val request = holdRequest(call, original, id, masking, options.maxBodyBytes, replayOf, network)
        val mock = if (network) null else sink.mockFor(request.method, request.url.toString())
        sink.emit("RequestStarted") {
            requestStarted(id, request, masking, request.body.capture(options.maxBodyBytes), mock?.id, replayOf)
        }
        sink.keepForReplay(id) { replay(call, id) }
        if (mock != null) {
            val answered = answer(chain, request, mock, id)
            val known = checkNotNull(mock.response).body.let { CapturedBody(it, truncated = false, size = it.size.toLong()) }
            val (mocked, body) = holdResponse(call, request, answered, known, id, masking, options.maxBodyBytes)
            sink.emit("ResponseReceived") { responseReceived(id, mocked, masking, checkNotNull(body)) }
            return mocked
        }
        val received =
            try {
                chain.proceed(request)
            } catch (e: Exception) {
                sink.emit("RequestFailed") { requestFailed(id, e) }
                throw e
            }
        if (received.code == SWITCHING_PROTOCOLS) {
            sink.emit("ResponseReceived") { responseReceived(id, received, masking, CapturedBody.NONE) }
            return received
        }
        val known = if (received.body.contentLength() == 0L) CapturedBody.EMPTY else null
        val (response, replaced) = holdResponse(call, request, received, known, id, masking, options.maxBodyBytes)
        if (replaced != null) {
            sink.emit("ResponseReceived") { responseReceived(id, response, masking, replaced) }
            return response
        }
        val capturing =
            CapturingResponseBody(response.body, options.maxBodyBytes) { captured ->
                sink.emit("ResponseReceived") { responseReceived(id, response, masking, captured) }
            }
        return response.newBuilder().body(capturing).build()
    }

    /** ADR-0111 A.1, C.1: [request] as its breakpoint lets it go, edited or not; an abort or a bad edit fails the call. */
    private fun holdRequest(
        call: Call,
        request: Request,
        id: String,
        masking: Masking,
        limit: Int,
        replayOf: String?,
        network: Boolean,
    ): Request {
        val stage = BreakpointStage.BREAKPOINT_STAGE_REQUEST
        val rule = sink.breakpointFor(request.method, request.url.toString(), stage) ?: return request
        val started = requestStarted(id, request, masking, request.body.capture(limit), replayOf = replayOf)
        val outcome =
            held(call, id, started) { BreakpointHit(rule_id = rule.id, stage = stage, request = started, timeout_ms = rule.timeout_ms) }
        val failure =
            when (outcome) {
                is BreakpointOutcome.Abort -> {
                    IOException(outcome.message)
                }

                is BreakpointOutcome.Resume -> {
                    try {
                        return edited(request, outcome, network) ?: request
                    } catch (e: IOException) {
                        e
                    }
                }
            }
        sink.emit("RequestStarted") { started }
        sink.emit("RequestFailed") { requestFailed(id, failure) }
        throw failure
    }

    /** [request] with the resume's fields; null when unchanged; throws an [IOException] for an edit OkHttp refuses. */
    private fun edited(
        request: Request,
        outcome: BreakpointOutcome.Resume,
        network: Boolean,
    ): Request? {
        val changes = outcome.changes ?: return null
        if (changes.method == null && changes.url == null && changes.body == null && !outcome.changesHeaders) return null
        val headers =
            Headers
                .Builder()
                .apply {
                    outcome.headers(request.headers.toList()).forEach { (n, v) ->
                        addUnsafeNonAscii(n, v)
                    }
                }.build()
        val url = changes.url?.let { it.toHttpUrlOrNull() ?: throw IOException("Breakpoint edit refused: not a URL: $it") } ?: request.url
        if (network && (url.host != request.url.host || url.port != request.url.port)) {
            throw IOException("Breakpoint edit refused: a network interceptor keeps the host and port")
        }
        val type = (headers["Content-Type"] ?: request.body?.contentType()?.toString())?.toMediaTypeOrNull()
        val body = changes.body?.toRequestBody(type) ?: request.body
        return try {
            request
                .newBuilder()
                .url(url)
                .headers(headers)
                .method(changes.method ?: request.method, body)
                .build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Breakpoint edit refused: ${e.message}", e)
        }
    }

    /**
     * ADR-0111 A.1, C.1: [response] as its breakpoint lets the app read it, with the body when it was replaced or
     * known as [body]; an abort fails the call. The held body is peeked, so the app still reads it whole.
     */
    private fun holdResponse(
        call: Call,
        request: Request,
        response: Response,
        body: CapturedBody?,
        id: String,
        masking: Masking,
        limit: Int,
    ): Pair<Response, CapturedBody?> {
        val stage = BreakpointStage.BREAKPOINT_STAGE_RESPONSE
        val rule = sink.breakpointFor(request.method, request.url.toString(), stage) ?: return response to body
        val seen =
            body ?: try {
                peek(response, limit)
            } catch (e: IOException) {
                response.close()
                sink.emit("RequestFailed") { requestFailed(id, e) }
                throw e
            }
        val outcome =
            try {
                held(call, id, null) {
                    BreakpointHit(
                        rule_id = rule.id,
                        stage = stage,
                        request = requestStarted(id, request, masking, request.body.capture(limit)),
                        response = responseReceived(id, response, masking, seen),
                        timeout_ms = rule.timeout_ms,
                    )
                }
            } catch (e: IOException) {
                response.close()
                throw e
            }
        val changes =
            when (outcome) {
                is BreakpointOutcome.Abort -> {
                    response.close()
                    val failure = IOException(outcome.message)
                    sink.emit("RequestFailed") { requestFailed(id, failure) }
                    throw failure
                }

                is BreakpointOutcome.Resume -> {
                    outcome.changes ?: return response to body
                }
            }
        val headers =
            Headers
                .Builder()
                .apply {
                    outcome.headers(response.headers.toList()).forEach { (n, v) ->
                        addUnsafeNonAscii(n, v)
                    }
                }.build()
        val edited = response.newBuilder().code(changes.status_code ?: response.code).headers(headers)
        val replaced = changes.body ?: return edited.build() to body
        response.close()
        val type = (headers["Content-Type"] ?: response.body.contentType()?.toString())?.toMediaTypeOrNull()
        return edited.body(replaced.toResponseBody(type)).build() to
            CapturedBody(replaced, truncated = false, size = replaced.size.toLong())
    }

    /** At most [limit] bytes of [response]'s body, read ahead of the app without consuming them. */
    private fun peek(
        response: Response,
        limit: Int,
    ): CapturedBody {
        val peek = response.body.source().peek()
        val more = peek.request(limit + 1L)
        val bytes = peek.readByteString(minOf(peek.buffer.size, limit.toLong()))
        val length = response.body.contentLength().takeIf { it >= 0 }
        return CapturedBody(bytes, truncated = more, size = if (more) length else bytes.size.toLong())
    }

    /**
     * Sends the [hit] and blocks the call's thread until the desktop answers (ADR-0111 B.1); a cancel or an interrupt
     * fails the call. [started] is captured before the failure when the request was not yet.
     */
    private fun held(
        call: Call,
        id: String,
        started: RequestStarted?,
        hit: () -> BreakpointHit,
    ): BreakpointOutcome {
        val failure: IOException =
            try {
                return runBlocking {
                    val outcome = async { sink.hold(hit()) }
                    var answered: BreakpointOutcome? = null
                    while (answered == null) {
                        answered = withTimeoutOrNull(CANCEL_POLL_MS) { outcome.await() }
                        if (answered == null && call.isCanceled()) {
                            outcome.cancel()
                            throw IOException("Canceled")
                        }
                    }
                    answered
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                InterruptedIOException("interrupted")
            } catch (e: IOException) {
                e
            }
        if (started != null) sink.emit("RequestStarted") { started }
        sink.emit("RequestFailed") { requestFailed(id, failure) }
        throw failure
    }

    /** ADR-0122 B.1: a clone of the app's call through its own client, read to the end so its response is captured whole. */
    private fun replay(
        call: Call,
        id: String,
    ): String? {
        val body = call.request().body
        if (body != null && (body.isOneShot() || body.isDuplex())) return "Its body could be sent only once"
        val clone = call.clone()
        replays[clone] = id
        clone.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    replays.remove(call)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    replays.remove(call)
                    try {
                        response.use { it.body.source().readAll(blackholeSink()) }
                    } catch (e: IOException) {
                        // Captured as the call's end by CapturingSource.
                    }
                }
            },
        )
        return null
    }

    /** ADR-0110 C.1: the rule's answer after its delay, without the network; a failure is an [IOException]. */
    private fun answer(
        chain: Interceptor.Chain,
        request: Request,
        rule: MockRule,
        id: String,
    ): Response {
        try {
            if (rule.delay_ms > 0) Thread.sleep(rule.delay_ms)
            if (chain.call().isCanceled()) throw IOException("Canceled")
            rule.failure?.let { throw IOException(it.message) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            val interrupted = InterruptedIOException("interrupted")
            sink.emit("RequestFailed") { requestFailed(id, interrupted) }
            throw interrupted
        } catch (e: IOException) {
            sink.emit("RequestFailed") { requestFailed(id, e) }
            throw e
        }
        val mocked = checkNotNull(rule.response)
        val headers = Headers.Builder().apply { for (header in mocked.headers) addUnsafeNonAscii(header.name, header.value_) }.build()
        val now = System.currentTimeMillis()
        val response =
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(mocked.status_code)
                .message("")
                .headers(headers)
                .body(mocked.body.toResponseBody(headers["Content-Type"]?.toMediaTypeOrNull()))
                .sentRequestAtMillis(now)
                .receivedResponseAtMillis(now)
                .build()
        return response
    }

    private companion object {
        const val SWITCHING_PROTOCOLS = 101

        // How often a held call checks that the app did not cancel it.
        const val CANCEL_POLL_MS = 50L
    }
}

/** The app's body, copied as the app reads it; [onDone] runs once, at its end or close (ADR-0018 C). */
private class CapturingResponseBody(
    private val delegate: ResponseBody,
    limit: Int,
    onDone: (CapturedBody) -> Unit,
) : ResponseBody() {
    private val source = CapturingSource(delegate.source(), limit, delegate.contentLength(), onDone).buffer()

    override fun contentType() = delegate.contentType()

    override fun contentLength() = delegate.contentLength()

    override fun source(): BufferedSource = source
}

private class CapturingSource(
    delegate: Source,
    private val limit: Int,
    private val knownLength: Long,
    private val onDone: (CapturedBody) -> Unit,
) : ForwardingSource(delegate) {
    private val captured = Buffer()
    private var total = 0L
    private val done = AtomicBoolean()

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        val read =
            try {
                super.read(sink, byteCount)
            } catch (e: Exception) {
                finish(endReached = false)
                throw e
            }
        if (read == -1L) {
            finish(endReached = true)
            return read
        }
        total += read
        val kept = minOf(read, limit - captured.size)
        if (kept > 0) sink.copyTo(captured, sink.size - read, kept)
        return read
    }

    override fun close() {
        finish(endReached = false)
        super.close()
    }

    private fun finish(endReached: Boolean) {
        if (!done.compareAndSet(false, true)) return
        val whole = endReached || total == knownLength
        val size = if (whole) total else knownLength.takeIf { it >= 0 }
        onDone(CapturedBody(captured.readByteString(), truncated = !whole || total > limit, size = size))
    }
}
