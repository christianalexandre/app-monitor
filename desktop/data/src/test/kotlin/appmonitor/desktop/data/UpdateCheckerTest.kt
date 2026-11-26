package appmonitor.desktop.data

import appmonitor.desktop.domain.AvailableUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class UpdateCheckerTest {
    private val release = AvailableUpdate("1.1.0", "https://github.com/x/y/releases/tag/1.1.0")

    @Test
    fun `versions compare by number, a snapshot before its release, anything else never newer`() {
        assertTrue(isNewer("1.1.0", "1.0.0"))
        assertTrue(isNewer("v1.0.1", "1.0.0"))
        assertTrue(isNewer("1.10.0", "1.9.9"))
        assertTrue(isNewer("1.0.0", "1.0.0-SNAPSHOT"))
        assertFalse(isNewer("1.0.0", "1.0.0"))
        assertFalse(isNewer("1.0.0", "1.1.0"))
        assertFalse(isNewer("1.1.0-rc1", "1.0.0"))
        assertFalse(isNewer("1.1.0", "dev"))
        assertFalse(isNewer("latest", "1.0.0"))
    }

    @Test
    fun `a newer release is found at start, the running one is not`() =
        withStorage { storage ->
            val newer = UpdateChecker(storage.settingRepository(), "1.0.0", { release }, scope())
            awaitTrue { newer.available.value == release }
            val same = UpdateChecker(storage.settingRepository(), "1.1.0", { release }, scope())
            delay(200)
            assertNull(same.available.value)
        }

    @Test
    fun `switched off it forgets what it found, stops asking, and the store keeps it off`() =
        withStorage { storage ->
            val calls = AtomicInteger()
            val checker = UpdateChecker(storage.settingRepository(), "1.0.0", { calls.incrementAndGet().let { release } }, scope())
            awaitTrue { checker.available.value == release }

            checker.setChecking(false)
            assertNull(checker.available.value)
            assertFalse(checker.checking.value)
            storage.flush()
            val restarted = UpdateChecker(storage.settingRepository(), "1.0.0", { calls.incrementAndGet().let { release } }, scope())
            awaitTrue { !restarted.checking.value }
            delay(200)
            assertEquals(1, calls.get(), "no check while off")
            assertNull(restarted.available.value)

            restarted.setChecking(true)
            awaitTrue { restarted.available.value == release }
            assertEquals(2, calls.get())
        }

    @Test
    fun `a failed check keeps what the last one found`() =
        withStorage { storage ->
            var answer: AvailableUpdate? = release
            val checker =
                UpdateChecker(storage.settingRepository(), "1.0.0", { answer ?: error("offline") }, scope(), period = 50.milliseconds)
            awaitTrue { checker.available.value == release }
            answer = null
            delay(300)
            assertEquals(release, checker.available.value)
        }

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    private suspend fun awaitTrue(condition: () -> Boolean) = withTimeout(5.seconds) { while (!condition()) delay(10) }
}
