package appmonitor.tools.fakeclient

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class HandshakeFailureTest {
    private var server: EmbeddedServer<*, *>? = null

    @AfterTest
    fun tearDown() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    @Test
    fun `fails when the first reply is not a HelloAck and the peer stays open`() =
        runBlocking {
            val port =
                serve {
                    incoming.receive()
                    val wrong = Envelope(channel = Protocol.CHANNEL_CORE, type = "Hello", payload = ByteString.EMPTY)
                    send(Frame.Binary(true, wrong.encode()))
                    awaitCancellation()
                }

            val error =
                withTimeout(TIMEOUT_MS) {
                    assertFailsWith<HandshakeException> { replay(Target("127.0.0.1", port)) }
                }

            assertContains(error.message.orEmpty(), "got ${Protocol.CHANNEL_CORE}/Hello")
        }

    @Test
    fun `fails when the peer never replies`() =
        runBlocking {
            val port =
                serve {
                    incoming.receive()
                    awaitCancellation()
                }

            val error =
                withTimeout(TIMEOUT_MS) {
                    assertFailsWith<HandshakeException> { replay(Target("127.0.0.1", port), handshakeTimeoutMs = 200) }
                }

            assertContains(error.message.orEmpty(), "no reply within 200 ms")
        }

    private suspend fun serve(handler: suspend DefaultWebSocketServerSession.() -> Unit): Int {
        val server =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                install(WebSockets)
                routing { webSocket(Protocol.WEBSOCKET_PATH) { handler() } }
            }
        server.startSuspend(wait = false)
        this.server = server
        return server.engine
            .resolvedConnectors()
            .first()
            .port
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
