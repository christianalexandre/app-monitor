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
import kotlinx.coroutines.withTimeoutOrNull

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
    HttpClient(CIO) { install(WebSockets) }.use { client ->
        client.webSocket(host = target.host, port = target.port, path = target.path) {
            send(Frame.Binary(true, hello.encode()))
            ack = awaitHelloAck(handshakeTimeoutMs)
            traffic.forEach { send(Frame.Binary(true, it.copy(timestamp_ms = System.currentTimeMillis()).encode())) }
        }
    }
    return checkNotNull(ack)
}

private suspend fun DefaultClientWebSocketSession.awaitHelloAck(timeoutMs: Long): HelloAck {
    val reply =
        withTimeoutOrNull(timeoutMs) { incoming.receiveCatching() }
            ?: fail("no reply within $timeoutMs ms")
    val frame = reply.getOrNull() ?: throw HandshakeException("Desktop closed before HelloAck: ${closeReason.await()}")
    if (frame !is Frame.Binary) fail("got a ${frame.frameType} frame")
    val envelope = runCatching { Envelope.ADAPTER.decode(frame.readBytes()) }.getOrNull() ?: fail("got a frame that is not an Envelope")
    if (envelope.channel != Protocol.CHANNEL_CORE || envelope.type != "HelloAck") fail("got ${envelope.channel}/${envelope.type}")
    return HelloAck.ADAPTER.decode(envelope.payload)
}

private suspend fun DefaultClientWebSocketSession.fail(reply: String): Nothing {
    val message = "Desktop did not answer Hello with HelloAck: $reply"
    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, message.take(MAX_CLOSE_REASON)))
    throw HandshakeException(message)
}

private const val HANDSHAKE_TIMEOUT_MS = 10_000L

// RFC 6455 caps the close frame payload at 125 bytes, 2 of them for the code.
private const val MAX_CLOSE_REASON = 123
