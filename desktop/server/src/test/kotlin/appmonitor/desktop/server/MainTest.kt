package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun `no arguments use the default port`() {
        assertEquals(Protocol.DEFAULT_PORT, parse().first)
    }

    @Test
    fun `a valid port is used`() {
        assertEquals(8080, parse("--port", "8080").first)
    }

    @Test
    fun `the proxy directory is read with the port in any order`() {
        assertEquals(ServerArgs(0, Path.of("ca")), parseOptions("--proxy", "ca", "--port", "0").first)
        assertNull(parseOptions("--port", "0").first?.proxyDirectory)
    }

    @Test
    fun `a bad port is a one-line error`() {
        listOf(
            arrayOf("--port", "abc"),
            arrayOf("--port"),
            arrayOf("--port", "70000"),
            arrayOf("--prot", "1"),
            arrayOf("--port", "1", "--proxy"),
            arrayOf("--port", "abc", "--proxy", "ca"),
        ).forEach { args ->
            val (port, err) = parse(*args)

            assertNull(port, args.joinToString(" "))
            assertEquals(1, err.trimEnd().lines().size, err)
            assertTrue(err.startsWith("Usage: server"), err)
        }
    }

    @Test
    fun `the shutdown hook stops a server whose start is still running`() =
        runBlocking<Unit> {
            val port = ServerSocket(0).use { it.localPort }
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val server =
                AppMonitorServer(
                    ServerConfig(host = "127.0.0.1", port = port, advertise = false),
                    startEngine = {
                        entered.complete(Unit)
                        gate.await()
                        it.startSuspend(wait = false)
                    },
                ) { _, _ -> error("not advertising") }
            val hooks = CopyOnWriteArrayList<Thread>()
            val starting = async(Dispatchers.Default) { runCatching { startWithShutdownHook(server, hooks::add) } }
            withTimeout(TIMEOUT_MS) { entered.await() }

            val hook = hooks.single().apply { start() }
            gate.complete(Unit)
            hook.join(TIMEOUT_MS)

            // The hook's stop may land before or after start returns; either way the port is freed.
            assertFalse(hook.isAlive)
            withTimeout(TIMEOUT_MS) { starting.await() }
            ServerSocket(port, 0, InetAddress.getByName("127.0.0.1")).close()
        }

    private fun parse(vararg args: String): Pair<Int?, String> = parseOptions(*args).let { (options, err) -> options?.port to err }

    private fun parseOptions(vararg args: String): Pair<ServerArgs?, String> {
        val err = ByteArrayOutputStream()
        val options = parseArgs(arrayOf(*args), PrintStream(err))
        return options to err.toString()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
