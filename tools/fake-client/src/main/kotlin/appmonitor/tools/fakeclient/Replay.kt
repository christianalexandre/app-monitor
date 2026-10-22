package appmonitor.tools.fakeclient

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.HelloAck
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.cio.webSocketRawSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Where a desktop listens. */
data class Target(
    val host: String,
    val port: Int = Protocol.DEFAULT_PORT,
    val path: String = Protocol.WEBSOCKET_PATH,
)

/** The desktop did not complete the handshake; [message] says what it sent instead. */
internal class HandshakeException(
    message: String,
) : Exception(message)

/** The desktop closed the session with an error after the handshake. */
internal class SessionClosedException(
    message: String,
) : Exception(message)

/**
 * Handshakes with [target], sends [traffic] stamped with the current time and closes. Returns the
 * `HelloAck`; throws [HandshakeException] on any other first reply or none in [handshakeTimeoutMs],
 * and [SessionClosedException] when the desktop answers the session with a close that is not normal.
 */
suspend fun replay(
    target: Target,
    hello: Envelope = Fixtures.hello,
    traffic: List<Envelope> = Fixtures.traffic,
    handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    closeTimeoutMs: Long = CLOSE_TIMEOUT_MS,
): HelloAck =
    try {
        HttpClient(CIO) { install(WebSockets) }.use { client ->
            // Raw: the default session completes closeReason with its own close, hiding the desktop's.
            // Not webSocketRaw: it swallows what its block throws.
            val session = client.webSocketRawSession(host = target.host, port = target.port, path = target.path)
            try {
                session.exchange(hello, traffic, handshakeTimeoutMs, closeTimeoutMs)
            } finally {
                session.cancel()
            }
        }
    } catch (e: CancellationException) {
        // Ktor cancels the outgoing channel when the connection drops mid-send.
        currentCoroutineContext().ensureActive()
        throw IOException("Connection to the desktop dropped", e)
    }

private suspend fun ClientWebSocketSession.exchange(
    hello: Envelope,
    traffic: List<Envelope>,
    handshakeTimeoutMs: Long,
    closeTimeoutMs: Long,
): HelloAck {
    send(Frame.Binary(true, hello.encode()))
    val ack = awaitHelloAck(handshakeTimeoutMs)
    val sent =
        sendOrDrop {
            traffic.forEach { send(Frame.Binary(true, it.copy(timestamp_ms = System.currentTimeMillis()).encode())) }
            send(Frame.Close(CloseReason(CloseReason.Codes.NORMAL, "")))
        }
    val reason = withTimeoutOrNull(closeTimeoutMs) { awaitClose() }
    if (reason != null && reason.code != CloseReason.Codes.NORMAL.code) {
        throw SessionClosedException("Desktop closed the session: $reason")
    }
    if (!sent || reason == null) throw IOException("Connection to the desktop dropped")
    return ack
}

/** False when the connection drops mid-send; Ktor then cancels or closes the outgoing channel. */
private suspend fun sendOrDrop(block: suspend () -> Unit): Boolean =
    try {
        block()
        true
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        false
    } catch (e: ClosedSendChannelException) {
        false
    }

/** The desktop's close, skipping other frames; null if the connection ends without one. */
private suspend fun ClientWebSocketSession.awaitClose(): CloseReason? {
    while (true) {
        val frame = incoming.receiveCatching().getOrNull() ?: return null
        if (frame is Frame.Close) return frame.readReason() ?: CloseReason(CloseReason.Codes.NORMAL, "")
    }
}

private suspend fun ClientWebSocketSession.awaitHelloAck(timeoutMs: Long): HelloAck {
    val reply =
        withTimeoutOrNull(timeoutMs) { incoming.receiveCatching() }
            ?: fail("no reply within $timeoutMs ms")
    val frame = reply.getOrNull()
    if (frame == null || frame is Frame.Close) {
        throw HandshakeException("Desktop closed before HelloAck: ${(frame as? Frame.Close)?.readReason() ?: "no close reason"}")
    }
    if (frame !is Frame.Binary) fail("got a ${frame.frameType} frame")
    val envelope = runCatching { Envelope.ADAPTER.decode(frame.readBytes()) }.getOrNull() ?: fail("got a frame that is not an Envelope")
    if (envelope.channel != Protocol.CHANNEL_CORE || envelope.type != "HelloAck") fail("got ${envelope.channel}/${envelope.type}")
    return runCatching { HelloAck.ADAPTER.decode(envelope.payload) }.getOrNull() ?: fail("got a malformed HelloAck")
}

private suspend fun ClientWebSocketSession.fail(reply: String): Nothing {
    val message = "Desktop did not answer Hello with HelloAck: $reply"
    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, message.truncateUtf8(MAX_CLOSE_REASON)))
    throw HandshakeException(message)
}

/** The longest prefix that fits in [maxBytes] of UTF-8, never splitting a surrogate pair. */
internal fun String.truncateUtf8(maxBytes: Int): String {
    var bytes = 0
    var end = 0
    while (end < length) {
        val codePoint = codePointAt(end)
        val size =
            when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
        if (bytes + size > maxBytes) break
        bytes += size
        end += Character.charCount(codePoint)
    }
    return substring(0, end)
}

private const val HANDSHAKE_TIMEOUT_MS = 10_000L
private const val CLOSE_TIMEOUT_MS = 10_000L

// RFC 6455 caps the close frame payload at 125 bytes, 2 of them for the code; the rest is UTF-8.
private const val MAX_CLOSE_REASON = 123
