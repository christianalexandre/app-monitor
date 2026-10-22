package appmonitor.sdk.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BackoffTest {
    @Test
    fun doublesUpToTheMaximumAndResets() {
        val backoff = Backoff(jitter = 0.0)
        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30).map { it.seconds }, List(7) { backoff.nextDelay() })
        backoff.reset()
        assertEquals(1.seconds, backoff.nextDelay())
    }

    @Test
    fun exhaustJumpsToTheMaximum() {
        val backoff = Backoff(jitter = 0.0)
        backoff.exhaust()
        assertEquals(30.seconds, backoff.nextDelay())
    }

    @Test
    fun jitterStaysWithinTwentyPercent() {
        val random = Random(42)
        repeat(1_000) {
            val delay = Backoff(random = random).nextDelay()
            assertTrue(delay >= 0.8.seconds && delay <= 1.2.seconds, "$delay")
        }
    }
}
