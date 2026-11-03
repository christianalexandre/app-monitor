package appmonitor.tools.fakeclient

import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.DeviceApproval
import appmonitor.desktop.server.ServerConfig
import appmonitor.protocol.Protocol
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocketRaw
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTest {
    private var server: EmbeddedServer<*, *>? = null

    @AfterTest
    fun tearDown() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    @Test
    fun `an unresolvable host exits with a one-line error`() =
        runBlocking {
            val (status, err) = run("--host", "no-such-desktop.invalid", "--port", "47474")

            assertEquals(1, status)
            assertOneLine(err, "Cannot reach the desktop at ws://no-such-desktop.invalid:47474")
        }

    @Test
    fun `an endpoint that is not a websocket exits with a one-line error`() =
        runBlocking {
            val port = serveNothing()

            val (status, err) = run("--host", "127.0.0.1", "--port", "$port")

            assertEquals(1, status)
            assertOneLine(err, "Cannot reach the desktop at ws://127.0.0.1:$port")
            assertTrue("404" in err, err)
        }

    @Test
    fun `a timeout of zero or less or a port out of range is a usage error`() =
        runBlocking {
            listOf(arrayOf("--timeout", "0"), arrayOf("--timeout", "-5"), arrayOf("--host", "h", "--port", "70000")).forEach { args ->
                val (status, err) = run(*args)

                assertEquals(2, status, args.joinToString(" "))
                assertTrue(err.startsWith("Usage: fake-client"), err)
            }
        }

    @Test
    fun `a connection dropped right after the upgrade exits with a one-line error`() =
        runBlocking {
            val peer = DroppingPeer(readHello = false)

            val (status, err) = run("--host", "127.0.0.1", "--port", "${peer.port}")
            peer.join()

            assertEquals(1, status)
            assertEquals(1, err.trimEnd().lines().size, err)
        }

    @Test
    fun `a desktop that closes the session with an error exits 1`() =
        runBlocking {
            val port = serveAckThenClose()

            val (status, err) = run("--host", "127.0.0.1", "--port", "$port")

            assertEquals(1, status)
            assertOneLine(err, "Desktop closed the session: CloseReason(reason=PROTOCOL_ERROR")
        }

    @Test
    fun `--help prints the usage to out and exits 0`() =
        runBlocking {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val status = withTimeout(TIMEOUT_MS) { run(arrayOf("--help"), PrintStream(out), PrintStream(err)) }

            assertEquals(0, status)
            assertTrue(out.toString().startsWith("Usage: fake-client"), out.toString())
            assertEquals("", err.toString())
        }

    @Test
    fun `--help as the value of another option is not a usage request`() =
        runBlocking {
            val (status, err) = run("--name", "--help", "--timeout", "1")

            assertEquals(1, status)
            assertFalse(err.startsWith("Usage"), err)
        }

    // Outside runBlocking: CoroutineScope.embeddedServer would make the server its child.
    private suspend fun serveNothing(): Int {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {}
        server.startSuspend(wait = false)
        this.server = server
        return server.engine
            .resolvedConnectors()
            .first()
            .port
    }

    private suspend fun serveAckThenClose(): Int {
        val server =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                install(WebSockets)
                routing { webSocketRaw(Protocol.WEBSOCKET_PATH) { ackThenClose() } }
            }
        server.startSuspend(wait = false)
        this.server = server
        return server.engine
            .resolvedConnectors()
            .first()
            .port
    }

    @Test
    fun `a device held for its approval is served once approved`() =
        runBlocking {
            val asked = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<Boolean>()
            val desktop =
                AppMonitorServer(
                    ServerConfig(host = "127.0.0.1", port = 0, advertise = false),
                    DeviceApproval {
                        asked.complete(Unit)
                        answer.await()
                    },
                )
            val port = desktop.start()
            launch { desktop.sessions.collect { session -> launch { session.envelopes.collect {} } } }
            val client = async { run("--host", "127.0.0.1", "--port", "$port", "--handshake-timeout", "$TIMEOUT_MS") }

            withTimeout(TIMEOUT_MS) { asked.await() }
            delay(500)
            assertFalse(client.isCompleted)
            answer.complete(true)

            assertEquals(0, client.await().first)
            desktop.stop()
        }

    @Test
    fun `the handshake timeout bounds an approval nobody gives`() =
        runBlocking {
            val desktop =
                AppMonitorServer(
                    ServerConfig(host = "127.0.0.1", port = 0, advertise = false),
                    DeviceApproval { awaitCancellation() },
                )
            val port = desktop.start()

            val (status, err) = run("--host", "127.0.0.1", "--port", "$port", "--handshake-timeout", "200")

            assertEquals(1, status)
            assertOneLine(err, "Desktop did not answer Hello with HelloAck: no reply within 200 ms")
            desktop.stop()
        }

    @Test
    fun `the handshake timeout is at least the time to click Approve, and a bad one is a usage error`() =
        runBlocking {
            assertTrue(HANDSHAKE_TIMEOUT_MS >= 60_000)
            assertEquals(2, run("--host", "h", "--handshake-timeout", "0").first)
        }

    private suspend fun run(vararg args: String): Pair<Int, String> {
        val err = ByteArrayOutputStream()
        val status = withTimeout(TIMEOUT_MS) { run(arrayOf(*args), PrintStream(ByteArrayOutputStream()), PrintStream(err)) }
        return status to err.toString()
    }

    private fun assertOneLine(
        err: String,
        prefix: String,
    ) {
        assertEquals(1, err.trimEnd().lines().size, err)
        assertTrue(err.startsWith(prefix), err)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
