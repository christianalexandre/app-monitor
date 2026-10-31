package appmonitor.sdk.core

import appmonitor.protocol.Protocol
import appmonitor.v1.DeliveryReport
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.HelloAck
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.server.websocket.webSocketRaw
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration.Companion.milliseconds

/**
 * A desktop for `commonTest` (ADR-0066 C): answers `Hello` accepting every channel, records each envelope,
 * `DeliveryReport`s in [reports]. [handler] replaces that after the upgrade; [silent] also stops answering pings.
 */
internal class StandInDesktop private constructor(
    private val engine: EmbeddedServer<*, *>,
    val port: Int,
    val hellos: Channel<Hello>,
    val envelopes: Channel<Envelope>,
    val reports: Channel<DeliveryReport>,
) {
    suspend fun stop() {
        engine.stopSuspend(0, 0)
    }

    companion object {
        suspend fun start(
            port: Int = 0,
            silent: Boolean = false,
            handler: (suspend WebSocketSession.() -> Unit)? = null,
        ): StandInDesktop {
            val hellos = Channel<Hello>(Channel.UNLIMITED)
            val envelopes = Channel<Envelope>(Channel.UNLIMITED)
            val reports = Channel<DeliveryReport>(Channel.UNLIMITED)
            val serve: suspend WebSocketSession.() -> Unit =
                handler ?: {
                    hellos.send(handshake())
                    if (silent) awaitCancellation()
                    for (frame in incoming) {
                        if (frame !is Frame.Binary) continue
                        val envelope = Envelope.ADAPTER.decode(frame.readBytes())
                        if (envelope.isReport()) reports.send(DeliveryReport.ADAPTER.decode(envelope.payload)) else envelopes.send(envelope)
                    }
                }
            val engine =
                bind(port) {
                    embeddedServer(CIO, host = LOOPBACK, port = port) {
                        install(WebSockets)
                        routing {
                            if (silent) {
                                webSocketRaw(Protocol.WEBSOCKET_PATH) { serve() }
                            } else {
                                webSocket(Protocol.WEBSOCKET_PATH) { serve() }
                            }
                        }
                    }
                }
            val bound =
                engine.engine
                    .resolvedConnectors()
                    .first()
                    .port
            return StandInDesktop(engine, bound, hellos, envelopes, reports)
        }

        // A desktop restarted on the port of one just stopped can find it still bound for a moment.
        private suspend fun bind(
            port: Int,
            create: () -> EmbeddedServer<*, *>,
        ): EmbeddedServer<*, *> {
            repeat(BIND_ATTEMPTS - 1) {
                val engine = create()
                try {
                    return engine.startSuspend(wait = false)
                } catch (e: Exception) {
                    // Ktor reports the bind failure as a cancellation; only this coroutine's own one ends here.
                    currentCoroutineContext().ensureActive()
                    if (port == 0) throw e
                    runCatching { engine.stopSuspend(0, 0) }
                    delay(100.milliseconds)
                }
            }
            return create().startSuspend(wait = false)
        }
    }
}

internal const val LOOPBACK: String = "127.0.0.1"

/** ADR-0166: the SDK's own report, which tests counting captured envelopes set apart. */
internal fun Envelope.isReport(): Boolean = channel == Protocol.CHANNEL_CORE && type == "DeliveryReport"

private const val BIND_ATTEMPTS = 20

/** Reads `Hello`, answers a `HelloAck` accepting all its channels. */
internal suspend fun WebSocketSession.handshake(): Hello {
    var hello: Hello? = null
    while (hello == null) {
        val frame = incoming.receive() as? Frame.Binary ?: continue
        hello = Hello.ADAPTER.decode(Envelope.ADAPTER.decode(frame.readBytes()).payload)
    }
    val ack = HelloAck(protocol_version = Protocol.VERSION, session_id = "stand-in", channels = hello.channels)
    val envelope = Envelope(channel = Protocol.CHANNEL_CORE, type = "HelloAck", payload = ack.encodeByteString())
    send(Frame.Binary(true, envelope.encode()))
    return hello
}

/** A raw TCP listener on [LOOPBACK]: accepts, never upgrades. */
internal suspend fun rawListener(): Pair<SelectorManager, ServerSocket> {
    val selector = SelectorManager(Dispatchers.Default)
    return selector to aSocket(selector).tcp().bind(LOOPBACK, 0)
}

/** A port nothing listens on. */
internal suspend fun closedPort(): Int {
    val (selector, socket) = rawListener()
    val port = (socket.localAddress as InetSocketAddress).port
    socket.close()
    selector.close()
    return port
}
