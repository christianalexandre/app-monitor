package appmonitor.desktop.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ShutdownHookTest {
    @Test
    fun `a hook whose stop never returns gives up within its timeout`() {
        val failures = CopyOnWriteArrayList<Throwable>()
        val hook = shutdownHook(timeout = 200.milliseconds, onFailure = { failures += it }) { awaitCancellation() }

        val started = System.nanoTime()
        hook.start()
        hook.join(TIMEOUT_MS)

        assertFalse(hook.isAlive)
        assertTrue((System.nanoTime() - started) / 1_000_000 < TIMEOUT_MS)
        assertIs<TimeoutException>(failures.single())
    }

    @Test
    fun `an Error thrown by stop is reported, not escaped`() {
        val failures = CopyOnWriteArrayList<Throwable>()
        val hook = shutdownHook(onFailure = { failures += it }) { throw NotImplementedError("stop") }

        hook.start()
        hook.join(TIMEOUT_MS)

        assertFalse(hook.isAlive)
        assertIs<NotImplementedError>(failures.single())
    }

    @Test
    fun `the shutdown hook stops a server whose start is still running`() =
        runBlocking<Unit> {
            val bound = CompletableDeferred<Int>()
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val server =
                AppMonitorServer(
                    ServerConfig(host = "127.0.0.1", port = 0, advertise = false),
                    startEngine = {
                        entered.complete(Unit)
                        gate.await()
                        it.startSuspend(wait = false)
                        bound.complete(
                            it.engine
                                .resolvedConnectors()
                                .first()
                                .port,
                        )
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
            ServerSocket(bound.await(), 0, InetAddress.getByName("127.0.0.1")).close()
        }

    private companion object {
        const val TIMEOUT_MS = 2_000L
    }
}
