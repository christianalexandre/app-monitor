package appmonitor.sdk.okhttp

import appmonitor.protocol.Protocol.CAPTURE_HEADER
import appmonitor.sdk.core.BreakpointOutcome
import appmonitor.sdk.core.CallCapture
import appmonitor.sdk.core.CaptureSink
import appmonitor.sdk.core.CapturedBody
import appmonitor.sdk.core.Masking
import appmonitor.sdk.core.MonitorSink
import appmonitor.v1.BreakpointHit
import appmonitor.v1.MockRule
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
 * captures nothing until `AppMonitor.start` runs. A call `AppMonitorKtor` already captured is sent on without
 * being captured again.
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
        // ADR-0129: sdk/ktor marks what it captured on Ktor's OkHttp engine; the mark stops here.
        if (original.header(CAPTURE_HEADER) != null) return chain.proceed(original.newBuilder().removeHeader(CAPTURE_HEADER).build())
        val options = sink.options
        if (options == null || original.tag(WebSocketCapture::class.java) != null) return chain.proceed(original)
        val capture = CallCapture(sink)
        val masking = okHttpMasking(options)
        val call = chain.call()
        val replayOf = replays[call]
        // ADR-0128: OkHttp throws when a network interceptor skips proceed() or changes the host or port.
        val network = chain.connection() != null
        val (request, held) = holdRequest(capture, call, original, masking, options.maxBodyBytes, replayOf, network)
        val sent = held ?: request.body.capture(options.maxBodyBytes)
        val mock = if (network) null else sink.mockFor(request.method, request.url.toString())
        capture.started { requestStarted(capture.id, request, masking, sent, mock?.id, replayOf) }
        sink.keepForReplay(capture.id) { replay(call, capture.id) }
        if (mock != null) {
            val answered = answer(capture, chain, request, mock)
            val known = CapturedBody.whole(checkNotNull(mock.response).body)
            val (mocked, body) = holdResponse(capture, call, request, sent, answered, known, masking, options.maxBodyBytes)
            capture.received { responseReceived(capture.id, mocked, masking, checkNotNull(body)) }
            return mocked
        }
        val received =
            try {
                chain.proceed(request)
            } catch (e: Exception) {
                capture.failed(e)
                throw e
            }
        if (received.code == CallCapture.SWITCHING_PROTOCOLS) {
            capture.received { responseReceived(capture.id, received, masking, CapturedBody.NONE) }
            return received
        }
        val known = if (received.body.contentLength() == 0L) CapturedBody.EMPTY else null
        val (response, replaced) = holdResponse(capture, call, request, sent, received, known, masking, options.maxBodyBytes)
        if (replaced != null) {
            capture.received { responseReceived(capture.id, response, masking, replaced) }
            return response
        }
        val capturing =
            CapturingResponseBody(response.body, options.maxBodyBytes) { captured ->
                capture.received { responseReceived(capture.id, response, masking, captured) }
            }
        return response.newBuilder().body(capturing).build()
    }

    /** [request] as its breakpoint lets it go, with its captured body when it was captured for the hit and not edited. */
    private fun holdRequest(
        capture: CallCapture,
        call: Call,
        request: Request,
        masking: Masking,
        limit: Int,
        replayOf: String?,
        network: Boolean,
    ): Pair<Request, CapturedBody?> {
        val rule = capture.requestBreakpoint(request.method, request.url.toString()) ?: return request to null
        val body = request.body.capture(limit)
        val started = requestStarted(capture.id, request, masking, body, replayOf = replayOf)
        return capture.holdRequest(rule, started, { hold(call, it) }) { resume ->
            if (resume.changesRequest) edited(request, resume, network) to null else request to body
        }
    }

    /** [request] with the resume's fields; throws an [IOException] for an edit OkHttp refuses. */
    private fun edited(
        request: Request,
        outcome: BreakpointOutcome.Resume,
        network: Boolean,
    ): Request {
        val changes = checkNotNull(outcome.changes)
        val headers = headersOf(outcome.headers(request.headers.toList()))
        val url = changes.url?.let { it.toHttpUrlOrNull() ?: throw IOException("${CallCapture.EDIT_REFUSED}not a URL: $it") } ?: request.url
        if (network && (url.host != request.url.host || url.port != request.url.port)) {
            throw IOException("${CallCapture.EDIT_REFUSED}a network interceptor keeps the host and port")
        }
        val type = (headers["Content-Type"] ?: request.body?.contentType()?.toString())?.toMediaTypeOrNull()
        val body = changes.body?.toRequestBody(type) ?: request.body
        return request
            .newBuilder()
            .url(url)
            .headers(headers)
            .method(changes.method ?: request.method, body)
            .build()
    }

    /**
     * [response] as its breakpoint lets the app read it, with the body when it was replaced or known as [body]. The
     * held body is peeked, so the app still reads it whole.
     */
    private fun holdResponse(
        capture: CallCapture,
        call: Call,
        request: Request,
        requestBody: CapturedBody,
        response: Response,
        body: CapturedBody?,
        masking: Masking,
        limit: Int,
    ): Pair<Response, CapturedBody?> {
        val rule = capture.responseBreakpoint(request.method, request.url.toString(), response.code) ?: return response to body
        val seen =
            body ?: try {
                peek(response, limit)
            } catch (e: IOException) {
                response.close()
                capture.failed(e)
                throw e
            }
        val resume =
            capture.holdResponse(
                rule,
                requestStarted(capture.id, request, masking, requestBody),
                responseReceived(capture.id, response, masking, seen),
                { hold(call, it) },
                { response.close() },
            ) ?: return response to body
        val changes = checkNotNull(resume.changes)
        val headers = headersOf(resume.headers(response.headers.toList()))
        val edited = response.newBuilder().code(changes.status_code ?: response.code).headers(headers)
        val replaced = changes.body ?: return edited.build() to body
        response.close()
        val type = (headers["Content-Type"] ?: response.body.contentType()?.toString())?.toMediaTypeOrNull()
        return edited.body(replaced.toResponseBody(type)).build() to CapturedBody.whole(replaced)
    }

    private fun headersOf(pairs: List<Pair<String, String>>): Headers =
        Headers.Builder().apply { for ((name, value) in pairs) addUnsafeNonAscii(name, value) }.build()

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

    /** Sends the [hit] and blocks the call's thread until the desktop answers (ADR-0111 B.1); a cancel or an interrupt throws. */
    private fun hold(
        call: Call,
        hit: BreakpointHit,
    ): BreakpointOutcome =
        try {
            runBlocking {
                val outcome = async { sink.hold(hit) }
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
            throw InterruptedIOException("interrupted")
        }

    /** ADR-0122 B.1: a clone of the app's call through its own client, read to the end so its response is captured whole. */
    private fun replay(
        call: Call,
        id: String,
    ): String? {
        val body = call.request().body
        if (body != null && (body.isOneShot() || body.isDuplex())) return CallCapture.SENT_ONCE
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

    /** ADR-0110 C.1: the rule's answer after its delay, without the network. */
    private fun answer(
        capture: CallCapture,
        chain: Interceptor.Chain,
        request: Request,
        rule: MockRule,
    ): Response {
        val mocked = capture.answer(rule) { delayMs -> waitFor(chain.call(), delayMs) }
        val headers = headersOf(mocked.headers.map { it.name to it.value_ })
        val now = System.currentTimeMillis()
        return Response
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
    }

    private fun waitFor(
        call: Call,
        delayMs: Long,
    ) {
        try {
            val until = System.nanoTime() + delayMs * 1_000_000
            while (!call.isCanceled() && System.nanoTime() < until) {
                Thread.sleep(minOf(CANCEL_POLL_MS, (until - System.nanoTime()) / 1_000_000).coerceAtLeast(0))
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("interrupted")
        }
        if (call.isCanceled()) throw IOException("Canceled")
    }

    private companion object {
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
