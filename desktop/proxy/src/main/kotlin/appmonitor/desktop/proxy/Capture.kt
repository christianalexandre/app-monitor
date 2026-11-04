package appmonitor.desktop.proxy

import appmonitor.protocol.Protocol
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import com.squareup.wire.Message
import io.netty.buffer.ByteBuf
import okio.Buffer
import java.util.UUID

/** The parts of one body kept for the store: at most [Protocol.MAX_BODY_BYTES], and the size that went by. */
internal class BodyCapture {
    private val kept = Buffer()
    var size = 0L
        private set

    fun add(content: ByteBuf) {
        val readable = content.readableBytes()
        val room = room(readable)
        if (room > 0) {
            val bytes = ByteArray(room)
            content.getBytes(content.readerIndex(), bytes)
            kept.write(bytes)
        }
        size += readable
    }

    fun add(
        bytes: ByteArray,
        count: Int = bytes.size,
    ) {
        kept.write(bytes, 0, room(count))
        size += count
    }

    private fun room(count: Int) = (Protocol.MAX_BODY_BYTES - kept.size).toInt().coerceIn(0, count)

    val truncated: Boolean get() = kept.size < size

    fun bytes() = kept.snapshot()
}

/**
 * One exchange the proxy relays, as an SDK's events (ADR-0101). Event loop only; each event goes to [record] once. One
 * [record] dropped cuts it: nothing more is sent, and a kept [RequestStarted] ends with a [RequestFailed] with [CUT].
 */
internal class Capture(
    private val method: String,
    private val url: String,
    private val headers: List<Header>,
    private val record: (Envelope) -> Boolean,
    private val clock: () -> Long,
) {
    private val requestId = UUID.randomUUID().toString()
    private val startedAtMs = clock()
    private val requestBody = BodyCapture()
    private var started = false
    private var cut = false
    private var open = false
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
                headers = headers,
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
        responseHeaders: List<Header>,
        body: BodyCapture,
    ) {
        if (ended) return
        requestDone()
        ended = true
        emit(
            ResponseReceived(
                request_id = requestId,
                status_code = status,
                headers = responseHeaders,
                body = body.bytes(),
                body_truncated = body.truncated,
                body_size = body.size,
            ),
            clock(),
        )
    }

    /** Frames and one [failed] are taken after [responded], until [failed] or [closed]: a WebSocket's (ADR-0193). */
    fun keepOpen() {
        open = true
    }

    /** The WebSocket ended with a close frame: nothing more is sent. */
    fun closed() {
        open = false
    }

    /** One gRPC message before the end, or a WebSocket frame; [message] keeps what [BodyCapture] keeps. */
    fun frame(
        sent: Boolean,
        message: BodyCapture,
        opcode: WebSocketFrame.Opcode = WebSocketFrame.Opcode.OPCODE_BINARY,
    ) {
        if (ended && !open) return
        requestDone()
        emit(
            WebSocketFrame(
                request_id = requestId,
                direction = if (sent) WebSocketFrame.Direction.DIRECTION_SENT else WebSocketFrame.Direction.DIRECTION_RECEIVED,
                opcode = opcode,
                payload = message.bytes(),
                payload_truncated = message.truncated,
                payload_size = message.size,
            ),
            clock(),
        )
    }

    fun failed(error: String) {
        if (ended && !open) return
        open = false
        requestDone()
        ended = true
        emit(RequestFailed(request_id = requestId, error = error), clock())
    }

    private fun emit(
        message: Message<*, *>,
        atMs: Long,
    ) {
        if (cut || record(envelope(message, atMs))) return
        cut = true
        if (message !is RequestStarted &&
            message !is RequestFailed
        ) {
            record(envelope(RequestFailed(request_id = requestId, error = CUT), clock()))
        }
    }

    companion object {
        /** The error of an exchange [ProxyRecording] dropped an event of. */
        const val CUT = "The proxy dropped part of this exchange: the store fell behind"

        private fun envelope(
            message: Message<*, *>,
            atMs: Long,
        ) = Envelope(
            channel = Protocol.CHANNEL_NETWORK,
            type = message::class.simpleName!!,
            payload = message.encodeByteString(),
            timestamp_ms = atMs,
        )
    }
}

/** Headers as stored, in order. */
internal fun headersOf(headers: Iterable<Map.Entry<CharSequence, CharSequence>>): List<Header> =
    headers.map { Header(it.key.toString(), it.value.toString()) }
