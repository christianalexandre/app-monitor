package appmonitor.sdk.core

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Retry delays doubling from [initial] to [max], each with ±[jitter] (ADR-0022). Not thread-safe. */
internal class Backoff(
    private val initial: Duration = 1.seconds,
    private val max: Duration = 30.seconds,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    private var next = initial

    fun nextDelay(): Duration {
        val base = next
        next = minOf(next * 2, max)
        return if (jitter == 0.0) base else base * (1 + random.nextDouble(-jitter, jitter))
    }

    fun reset() {
        next = initial
    }

    /** The next delay is the longest one, e.g. after the desktop rejected the device. */
    fun exhaust() {
        next = max
    }
}
