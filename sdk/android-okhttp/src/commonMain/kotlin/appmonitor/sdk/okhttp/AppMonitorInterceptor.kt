package appmonitor.sdk.okhttp

import appmonitor.v1.MockRule
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends each call's request, response and failure to the running `AppMonitor` (ADR-0017, ADR-0018), and
 * answers a call the desktop's mock rule matches (ADR-0110). Add it with `addInterceptor`, one event set per
 * call; `addNetworkInterceptor` gives one per network hop, with bodies still compressed as on the wire. It
 * captures nothing until `AppMonitor.start` runs.
 */
public class AppMonitorInterceptor : Interceptor {
    private val sink: CaptureSink

    // ADR-0026: beside an internal primary constructor, this one would be missing from the ABI dump.
    public constructor() {
        sink = MonitorSink
    }

    internal constructor(sink: CaptureSink) {
        this.sink = sink
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val options = sink.options
        if (options == null || request.tag(WebSocketCapture::class.java) != null) return chain.proceed(request)
        val id = newRequestId()
        val masking = Masking(options)
        val mock = sink.mockFor(request.method, request.url.toString())
        sink.emit("RequestStarted") { requestStarted(id, request, masking, request.body.capture(options.maxBodyBytes), mock?.id) }
        if (mock != null) return answer(chain, request, mock, id, masking)
        val response =
            try {
                chain.proceed(request)
            } catch (e: Exception) {
                sink.emit("RequestFailed") { requestFailed(id, e) }
                throw e
            }
        val body = response.body
        if (response.code == SWITCHING_PROTOCOLS || body.contentLength() == 0L) {
            val empty = if (response.code == SWITCHING_PROTOCOLS) CapturedBody.NONE else CapturedBody.EMPTY
            sink.emit("ResponseReceived") { responseReceived(id, response, masking, empty) }
            return response
        }
        val capturing =
            CapturingResponseBody(body, options.maxBodyBytes) { captured ->
                sink.emit("ResponseReceived") { responseReceived(id, response, masking, captured) }
            }
        return response.newBuilder().body(capturing).build()
    }

    /** ADR-0110 C.1: the rule's answer after its delay, without the network; a failure is an [IOException]. */
    private fun answer(
        chain: Interceptor.Chain,
        request: Request,
        rule: MockRule,
        id: String,
        masking: Masking,
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
        val body = CapturedBody(mocked.body, truncated = false, size = mocked.body.size.toLong())
        sink.emit("ResponseReceived") { responseReceived(id, response, masking, body) }
        return response
    }

    private companion object {
        const val SWITCHING_PROTOCOLS = 101
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
