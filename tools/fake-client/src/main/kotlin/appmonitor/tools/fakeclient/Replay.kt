package appmonitor.tools.fakeclient

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.HelloAck
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.TimeSource

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

/**
 * Handshakes with [target], sends [traffic] stamped with the current time and closes. Returns the
 * `HelloAck`; throws [HandshakeException] on any other first reply or none in [handshakeTimeoutMs].
 */
suspend fun replay(
    target: Target,
    hello: Envelope = Fixtures.hello,
    traffic: List<Envelope> = Fixtures.traffic,
    handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
): HelloAck {
    var ack: HelloAck? = null
    try {
        HttpClient(CIO) { install(WebSockets) }.use { client ->
            client.webSocket(host = target.host, port = target.port, path = target.path) {
                send(Frame.Binary(true, hello.encode()))
                ack = awaitHelloAck(handshakeTimeoutMs)
                traffic.forEach { send(Frame.Binary(true, it.copy(timestamp_ms = System.currentTimeMillis()).encode())) }
            }
        }
    } catch (e: CancellationException) {
        // Ktor cancels the outgoing channel when the connection drops mid-send.
        currentCoroutineContext().ensureActive()
        throw IOException("Connection to the desktop dropped", e)
    }
    return checkNotNull(ack)
}

private suspend fun DefaultClientWebSocketSession.awaitHelloAck(timeoutMs: Long): HelloAck {
    val started = TimeSource.Monotonic.markNow()
    val reply =
        withTimeoutOrNull(timeoutMs) { incoming.receiveCatching() }
            ?: fail("no reply within $timeoutMs ms")
    val frame =
        reply.getOrNull()
            ?: throw HandshakeException(
                "Desktop closed before HelloAck: ${closeReason.awaitWithin(timeoutMs - started.elapsedNow().inWholeMilliseconds)}",
            )
    if (frame !is Frame.Binary) fail("got a ${frame.frameType} frame")
    val envelope = runCatching { Envelope.ADAPTER.decode(frame.readBytes()) }.getOrNull() ?: fail("got a frame that is not an Envelope")
    if (envelope.channel != Protocol.CHANNEL_CORE || envelope.type != "HelloAck") fail("got ${envelope.channel}/${envelope.type}")
    return runCatching { HelloAck.ADAPTER.decode(envelope.payload) }.getOrNull() ?: fail("got a malformed HelloAck")
}

private suspend fun DefaultClientWebSocketSession.fail(reply: String): Nothing {
    val message = "Desktop did not answer Hello with HelloAck: $reply"
    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, message.truncateUtf8(MAX_CLOSE_REASON)))
    throw HandshakeException(message)
}

/** The close reason, or why there is none; Ktor never completes it if the connection drops while sending. */
internal suspend fun Deferred<CloseReason?>.awaitWithin(timeoutMs: Long): String =
    withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) { await() }?.toString() ?: "no close reason in time"

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

// RFC 6455 caps the close frame payload at 125 bytes, 2 of them for the code; the rest is UTF-8.
private const val MAX_CLOSE_REASON = 123
