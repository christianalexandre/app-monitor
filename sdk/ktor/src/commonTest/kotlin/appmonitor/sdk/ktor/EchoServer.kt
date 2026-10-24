package appmonitor.sdk.ktor

import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.v1.Envelope
import appmonitor.v1.WebSocketFrame
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** A WebSocket server on the loopback: echoes text and binary frames, closes with `1000 bye` on the text `close`. */
internal class EchoServer private constructor(
    private val engine: EmbeddedServer<*, *>,
    val port: Int,
) {
    suspend fun stop() {
        engine.stopSuspend(0, 0)
    }

    companion object {
        suspend fun start(): EchoServer {
            val engine =
                embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                    install(WebSockets)
                    routing {
                        webSocket("/echo") {
                            for (frame in incoming) {
                                when {
                                    frame is Frame.Text && frame.readText() == "close" -> {
                                        close(
                                            CloseReason(CloseReason.Codes.NORMAL, "bye"),
                                        )
                                    }

                                    frame is Frame.Text || frame is Frame.Binary -> {
                                        send(frame.copy())
                                    }
                                }
                            }
                        }
                    }
                }
            engine.startSuspend(wait = false)
            val port =
                engine.engine
                    .resolvedConnectors()
                    .first()
                    .port
            return EchoServer(engine, port)
        }
    }
}

/** Records envelopes sent from any thread, as frames are. */
internal class ConcurrentSink(
    override val options: AppMonitorOptions? = AppMonitorOptions(),
) : CaptureSink {
    private val envelopes = Channel<Envelope>(Channel.UNLIMITED)

    override fun send(envelope: Envelope) {
        envelopes.trySend(envelope)
    }

    suspend fun next(type: String): Envelope = withTimeout(5.seconds) { envelopes.receive() }.also { assertEquals(type, it.type) }

    /** Every frame recorded until none comes for a second. */
    suspend fun frames(): List<WebSocketFrame> =
        buildList {
            while (true) {
                val envelope = withTimeoutOrNull(1.seconds) { envelopes.receive() } ?: break
                assertEquals("WebSocketFrame", envelope.type)
                add(WebSocketFrame.ADAPTER.decode(envelope.payload))
            }
        }

    val pending: Envelope? get() = envelopes.tryReceive().getOrNull()
}
