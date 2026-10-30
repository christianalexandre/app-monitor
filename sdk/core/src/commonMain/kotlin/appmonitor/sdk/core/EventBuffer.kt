package appmonitor.sdk.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.Channel

/** One encoded envelope waiting to be written, with its channel for the `HelloAck` filter; [onDropped] when never written. */
internal class BufferedFrame(
    val channel: String,
    val bytes: ByteArray,
    val onDropped: (() -> Unit)? = null,
)

/**
 * Offline ring buffer (SDD 04): [offer] never blocks and drops the oldest frame once
 * [maxCount] or [maxBytes] is reached; [take] serves a single consumer.
 */
internal class EventBuffer(
    private val maxCount: Int = MAX_COUNT,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val lock = SynchronizedObject()
    private val frames = ArrayDeque<BufferedFrame>()
    private var bytes = 0L
    private val available = Channel<Unit>(Channel.CONFLATED)

    /** Frames dropped to make room, for tests until DEBT-0023 reports them. */
    val dropped: Long get() = synchronized(lock) { droppedCount }

    private var droppedCount = 0L

    val size: Int get() = synchronized(lock) { frames.size }

    fun offer(frame: BufferedFrame) {
        val evicted =
            synchronized(lock) {
                frames.addLast(frame)
                bytes += frame.bytes.size
                buildList {
                    while (frames.size > maxCount || bytes > maxBytes) {
                        add(frames.removeFirst().also { bytes -= it.bytes.size })
                        droppedCount++
                    }
                }
            }
        evicted.forEach { it.onDropped?.invoke() }
        available.trySend(Unit)
    }

    /** The oldest frame, removed; suspends while the buffer is empty. */
    suspend fun take(): BufferedFrame {
        while (true) {
            poll()?.let { return it }
            available.receive()
        }
    }

    fun clear() {
        val cleared =
            synchronized(lock) {
                frames.toList().also {
                    frames.clear()
                    bytes = 0
                }
            }
        cleared.forEach { it.onDropped?.invoke() }
    }

    private fun poll(): BufferedFrame? =
        synchronized(lock) {
            frames.removeFirstOrNull()?.also { bytes -= it.bytes.size }
        }

    companion object {
        const val MAX_COUNT: Int = 4096
        const val MAX_BYTES: Long = 8L shl 20
    }
}
