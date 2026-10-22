package appmonitor.tools.fakeclient

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
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
