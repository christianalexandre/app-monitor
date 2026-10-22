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
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    fun `a multibyte close reason fits in 123 UTF-8 bytes`() =
        runBlocking {
            val reason = CompletableDeferred<CloseReason?>()
            val port =
                serve {
                    incoming.receive()
                    val wrong = Envelope(channel = "é".repeat(100), type = "Hello", payload = ByteString.EMPTY)
                    send(Frame.Binary(true, wrong.encode()))
                    reason.complete(closeReason.await())
                }

            withTimeout(TIMEOUT_MS) {
                assertFailsWith<HandshakeException> { replay(Target("127.0.0.1", port)) }
                val message = assertNotNull(reason.await()).message

                assertEquals(CloseReason.Codes.PROTOCOL_ERROR.code, reason.await()?.code)
                assertTrue(message.startsWith("Desktop did not answer Hello with HelloAck: got éé"), message)
                assertTrue(message.encodeToByteArray().size in 122..123, "${message.encodeToByteArray().size} bytes")
            }
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

    @Test
    fun `fails when the HelloAck payload is malformed`() =
        runBlocking {
            val port =
                serve {
                    incoming.receive()
                    val ack = Envelope(channel = Protocol.CHANNEL_CORE, type = "HelloAck", payload = MALFORMED)
                    send(Frame.Binary(true, ack.encode()))
                    awaitCancellation()
                }

            val error =
                withTimeout(TIMEOUT_MS) {
                    assertFailsWith<HandshakeException> { replay(Target("127.0.0.1", port)) }
                }

            assertContains(error.message.orEmpty(), "got a malformed HelloAck")
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

        // Field 1 as a length-delimited value claiming 127 bytes that never come.
        val MALFORMED = ByteString.of(0x0A, 0x7F)
    }
}
