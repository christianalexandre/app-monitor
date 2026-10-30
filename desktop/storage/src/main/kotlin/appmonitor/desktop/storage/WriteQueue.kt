package appmonitor.desktop.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Items for one writer on [dispatcher], [maxBatch] at a time, bounded by [maxBytes] (SDD 09): a failed batch's
 * [counted] items go to [health], [retry] returns how many it wrote after all, [written] sees each batch, [dropped] what [close] left.
 */
internal class WriteQueue<T>(
    private val maxBytes: Long,
    private val maxBatch: Int,
    dispatcher: CoroutineDispatcher,
    private val sizeOf: (T) -> Long,
    private val counted: (T) -> Boolean,
    private val write: (List<T>) -> Unit,
    private val retry: () -> Int = { 0 },
    private val written: (List<T>) -> Unit = {},
    private val dropped: (List<T>) -> Unit = {},
) {
    private val pending = AtomicLong()

    private val items = Channel<T>(Channel.UNLIMITED)
    private val queuedBytes = MutableStateFlow(0L)
    private val admission = Mutex()
    private val mutableHealth = MutableStateFlow(StorageHealth())

    val health: StateFlow<StorageHealth> = mutableHealth.asStateFlow()

    @Volatile
    var highWaterBytes = 0L
        private set

    /** How many [enqueue]s found the queue full and waited. */
    @Volatile
    var fullWaits = 0L
        private set

    private val writer: Job = CoroutineScope(dispatcher).launch { drain() }

    /** Suspends while the queue holds [maxBytes]; an item bigger than that waits for an empty queue. Returns false once closed. */
    suspend fun enqueue(item: T): Boolean {
        val size = sizeOf(item)
        return admission.withLock {
            queuedBytes.value.let { if (it != 0L && it + size > maxBytes) fullWaits++ }
            queuedBytes.first { it == 0L || it + size <= maxBytes }
            if (items.trySend(item).isFailure) return@withLock false
            if (counted(item)) pending.incrementAndGet()
            queuedBytes.update { it + size }.also { highWaterBytes = maxOf(highWaterBytes, queuedBytes.value) }
            true
        }
    }

    /**
     * Stops taking items and waits up to [timeout] for the writer to finish what is queued; returns
     * how many [counted] items were not written, the batch being written when it gave up included.
     */
    suspend fun close(timeout: Duration): Long {
        items.close()
        if (withTimeoutOrNull(timeout) { writer.join() } != null) return 0
        writer.cancel()
        val lost = pending.get()
        // Wakes an enqueue waiting for room, which then sees the channel closed.
        queuedBytes.value = 0
        return lost
    }

    /** Runs [block] once the writer has left its last batch: at once, or later after a [close] that timed out. */
    fun afterWriter(block: () -> Unit) {
        writer.invokeOnCompletion { block() }
    }

    private suspend fun drain() {
        try {
            while (true) {
                // A blocking write never suspends, so a cancelled writer would go on draining.
                currentCoroutineContext().ensureActive()
                writeBatch(items.receiveCatching().getOrNull() ?: break)
            }
        } finally {
            // A writer ended by an Error leaves no one to read: later enqueues must return false, not wait.
            items.close()
            queuedBytes.value = 0
            val left = generateSequence { items.tryReceive().getOrNull() }.toList()
            if (left.isNotEmpty()) dropped(left)
        }
    }

    private fun writeBatch(first: T) {
        val batch = mutableListOf(first)
        while (batch.size < maxBatch) batch += items.tryReceive().getOrNull() ?: break
        val count = batch.count(counted)
        val recovered = retry()
        if (recovered > 0) mutableHealth.update { StorageHealth(notSaved = it.notSaved - recovered) }
        try {
            write(batch)
            if (count > 0) mutableHealth.update { it.copy(lastError = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            mutableHealth.update { StorageHealth(notSaved = it.notSaved + count, lastError = e.toString()) }
            if (e !is Exception) throw e
        }
        pending.addAndGet(-count.toLong())
        queuedBytes.update { bytes -> bytes - batch.sumOf(sizeOf) }
        written(batch)
    }
}

/** Items lost to failed writes since the store opened; [lastError] is cleared by the next batch or retry written. */
data class StorageHealth(
    val notSaved: Long = 0,
    val lastError: String? = null,
)
