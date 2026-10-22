package appmonitor.sdk.okhttp

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends each call's request, response and failure to the running `AppMonitor` (ADR-0017, ADR-0018).
 * Add it with `addInterceptor` (one event set per call) or `addNetworkInterceptor` (one per network
 * hop); it captures nothing until `AppMonitor.start` runs.
 */
public class AppMonitorInterceptor internal constructor(
    private val sink: CaptureSink,
) : Interceptor {
    public constructor() : this(MonitorSink)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val options = sink.options
        if (options == null || request.tag(WebSocketCapture::class.java) != null) return chain.proceed(request)
        val id = newRequestId()
        val masking = Masking(options)
        sink.emit("RequestStarted") { requestStarted(id, request, masking, request.body.capture(options.maxBodyBytes)) }
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
