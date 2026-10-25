package appmonitor.desktop.proxy

import appmonitor.protocol.Protocol
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import com.squareup.wire.Message
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpHeaders
import okio.Buffer
import java.util.UUID

/** The parts of one body kept for the store: at most [Protocol.MAX_BODY_BYTES], and the size that went by. */
internal class BodyCapture {
    private val kept = Buffer()
    var size = 0L
        private set

    fun add(content: ByteBuf) {
        val readable = content.readableBytes()
        val room = (Protocol.MAX_BODY_BYTES - kept.size).toInt().coerceIn(0, readable)
        if (room > 0) {
            val bytes = ByteArray(room)
            content.getBytes(content.readerIndex(), bytes)
            kept.write(bytes)
        }
        size += readable
    }

    val truncated: Boolean get() = kept.size < size

    fun bytes() = kept.snapshot()
}

/**
 * One exchange the proxy relays, written as the events an SDK sends (ADR-0101): [RequestStarted] once the request is
 * complete or fails, then [ResponseReceived] or [RequestFailed]. Event loop only; each event goes to [record] once.
 */
internal class Capture(
    private val method: String,
    private val url: String,
    private val headers: HttpHeaders,
    private val record: (Envelope) -> Unit,
    private val clock: () -> Long,
) {
    private val requestId = UUID.randomUUID().toString()
    private val startedAtMs = clock()
    private val requestBody = BodyCapture()
    private var started = false
    var ended = false
        private set

    fun requestContent(content: ByteBuf) = requestBody.add(content)

    /** The request went out in full; idempotent. */
    fun requestDone() {
        if (started) return
        started = true
        emit(
            RequestStarted(
                request_id = requestId,
                method = method,
                url = url,
                headers = headersOf(headers),
                body = requestBody.bytes(),
                body_truncated = requestBody.truncated,
                body_size = requestBody.size,
                capture_layer = CaptureLayer.CAPTURE_LAYER_PROXY,
            ),
            startedAtMs,
        )
    }

    fun responded(
        status: Int,
        responseHeaders: HttpHeaders,
        body: BodyCapture,
    ) {
        if (ended) return
        requestDone()
        ended = true
        emit(
            ResponseReceived(
                request_id = requestId,
                status_code = status,
                headers = headersOf(responseHeaders),
                body = body.bytes(),
                body_truncated = body.truncated,
                body_size = body.size,
            ),
            clock(),
        )
    }

    fun failed(error: String) {
        if (ended) return
        requestDone()
        ended = true
        emit(RequestFailed(request_id = requestId, error = error), clock())
    }

    private fun emit(
        message: Message<*, *>,
        atMs: Long,
    ) = record(
        Envelope(
            channel = Protocol.CHANNEL_NETWORK,
            type = message::class.simpleName!!,
            payload = message.encodeByteString(),
            timestamp_ms = atMs,
        ),
    )

    private fun headersOf(headers: HttpHeaders) = headers.map { Header(it.key, it.value) }
}
