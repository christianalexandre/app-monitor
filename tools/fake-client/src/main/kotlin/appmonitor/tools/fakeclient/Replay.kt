package appmonitor.tools.fakeclient

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.HelloAck
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes

/** Where a desktop listens. */
internal data class Target(
    val host: String,
    val port: Int = Protocol.DEFAULT_PORT,
    val path: String = Protocol.WEBSOCKET_PATH,
)

/**
 * Connects to [target], handshakes with [hello], sends [traffic] stamped with the current time
 * and closes. Returns the desktop's `HelloAck`.
 */
internal suspend fun replay(
    target: Target,
    hello: Envelope = Fixtures.hello,
    traffic: List<Envelope> = Fixtures.traffic,
): HelloAck {
    var ack: HelloAck? = null
    HttpClient(CIO) { install(WebSockets) }.use { client ->
        client.webSocket(host = target.host, port = target.port, path = target.path) {
            send(Frame.Binary(true, hello.encode()))
            val reply = incoming.receiveCatching().getOrNull() as? Frame.Binary
            val envelope = reply?.let { Envelope.ADAPTER.decode(it.readBytes()) }
            check(envelope?.channel == Protocol.CHANNEL_CORE && envelope.type == "HelloAck") {
                "Desktop did not answer Hello with HelloAck: ${closeReason.await()}"
            }
            ack = HelloAck.ADAPTER.decode(envelope.payload)
            traffic.forEach { send(Frame.Binary(true, it.copy(timestamp_ms = System.currentTimeMillis()).encode())) }
        }
    }
    return checkNotNull(ack)
}
