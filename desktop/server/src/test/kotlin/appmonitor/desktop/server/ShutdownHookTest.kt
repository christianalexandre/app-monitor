package appmonitor.desktop.server

import kotlinx.coroutines.awaitCancellation
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

    private companion object {
        const val TIMEOUT_MS = 2_000L
    }
}
