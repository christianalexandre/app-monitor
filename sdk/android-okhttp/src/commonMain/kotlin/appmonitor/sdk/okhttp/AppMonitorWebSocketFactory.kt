package appmonitor.sdk.okhttp

import appmonitor.v1.WebSocketFrame
import appmonitor.v1.WebSocketFrame.Direction
import appmonitor.v1.WebSocketFrame.Opcode
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * A [WebSocket.Factory] that sends the upgrade and every text, binary and close frame of its
 * WebSockets to the running `AppMonitor` (ADR-0017); use it instead of the `OkHttpClient` in
 * `newWebSocket`. It captures nothing until `AppMonitor.start` runs.
 */
public class AppMonitorWebSocketFactory internal constructor(
    private val delegate: WebSocket.Factory,
    private val sink: CaptureSink,
) : WebSocket.Factory {
    public constructor(delegate: WebSocket.Factory) : this(delegate, MonitorSink)

    override fun newWebSocket(
        request: Request,
        listener: WebSocketListener,
    ): WebSocket {
        val options = sink.options ?: return delegate.newWebSocket(request, listener)
        val capture = WebSocketCapture(newRequestId(), sink, Masking(options), options.maxBodyBytes)
        sink.emit("RequestStarted") { requestStarted(capture.id, request, capture.masking, CapturedBody.NONE) }
        val socket = CapturingWebSocket(capture)
        val tagged = request.newBuilder().tag(WebSocketCapture::class.java, capture).build()
        socket.bind(delegate.newWebSocket(tagged, CapturingListener(listener, socket, capture)))
        return socket
    }
}

/** One WebSocket's capture; as a request tag it tells [AppMonitorInterceptor] to leave the upgrade alone. */
internal class WebSocketCapture(
    val id: String,
    private val sink: CaptureSink,
    val masking: Masking,
    private val limit: Int,
) {
    // A reply must not be recorded before the frame it answers.
    fun <T> sent(write: WebSocketCapture.() -> T): T = synchronized(this) { write() }

    fun frame(
        direction: Direction,
        opcode: Opcode,
        payload: ByteString,
    ) = synchronized(this) {
        sink.emit("WebSocketFrame") {
            WebSocketFrame(
                request_id = id,
                direction = direction,
                opcode = opcode,
                payload = payload.substring(0, minOf(payload.size, limit)),
                payload_truncated = payload.size > limit,
                payload_size = payload.size.toLong(),
            )
        }
    }

    fun close(
        direction: Direction,
        code: Int,
        reason: String,
    ) = frame(direction, Opcode.OPCODE_CLOSE, Buffer().writeShort(code).writeUtf8(reason).readByteString())

    fun opened(response: Response) = sink.emit("ResponseReceived") { responseReceived(id, response, masking, CapturedBody.NONE) }

    fun failed(error: Throwable) = sink.emit("RequestFailed") { requestFailed(id, error) }
}

/** Records what the app sends; the app's listener gets this socket, so its replies are recorded too. */
private class CapturingWebSocket(
    private val capture: WebSocketCapture,
) : WebSocket {
    @Volatile private var delegate: WebSocket? = null

    private val socket: WebSocket get() = checkNotNull(delegate)

    // Callbacks may run on OkHttp's thread before newWebSocket returns.
    fun bind(webSocket: WebSocket) {
        delegate = webSocket
    }

    override fun request(): Request = socket.request()

    override fun queueSize(): Long = socket.queueSize()

    override fun send(text: String): Boolean =
        capture.sent { socket.send(text).also { if (it) frame(Direction.DIRECTION_SENT, Opcode.OPCODE_TEXT, text.encodeUtf8()) } }

    override fun send(bytes: ByteString): Boolean =
        capture.sent { socket.send(bytes).also { if (it) frame(Direction.DIRECTION_SENT, Opcode.OPCODE_BINARY, bytes) } }

    override fun close(
        code: Int,
        reason: String?,
    ): Boolean = capture.sent { socket.close(code, reason).also { if (it) close(Direction.DIRECTION_SENT, code, reason.orEmpty()) } }

    override fun cancel() = socket.cancel()
}

private class CapturingListener(
    private val app: WebSocketListener,
    private val socket: CapturingWebSocket,
    private val capture: WebSocketCapture,
) : WebSocketListener() {
    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {
        socket.bind(webSocket)
        capture.opened(response)
        app.onOpen(socket, response)
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) {
        socket.bind(webSocket)
        capture.frame(Direction.DIRECTION_RECEIVED, Opcode.OPCODE_TEXT, text.encodeUtf8())
        app.onMessage(socket, text)
    }

    override fun onMessage(
        webSocket: WebSocket,
        bytes: ByteString,
    ) {
        socket.bind(webSocket)
        capture.frame(Direction.DIRECTION_RECEIVED, Opcode.OPCODE_BINARY, bytes)
        app.onMessage(socket, bytes)
    }

    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        socket.bind(webSocket)
        capture.close(Direction.DIRECTION_RECEIVED, code, reason)
        app.onClosing(socket, code, reason)
    }

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        socket.bind(webSocket)
        app.onClosed(socket, code, reason)
    }

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        socket.bind(webSocket)
        capture.failed(t)
        app.onFailure(socket, t, response)
    }
}
