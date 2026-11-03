package appmonitor.sdk.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** The session's last [capacity] requests a capture module can send again, their bodies within [maxBytes] (ADR-0111 D.1, ADR-0122 A.1). */
internal class RecentRequests(
    private val capacity: Int = CAPACITY,
    private val maxBytes: Long = MAX_BYTES,
) {
    private class Kept(
        val bytes: Long,
        val replay: () -> String?,
    )

    private val lock = SynchronizedObject()
    private val kept = LinkedHashMap<String, Kept>()
    private var bytes = 0L

    /** [bodyBytes] is what [replay] holds on to, negative when unknown; the newest stays whatever its size. */
    fun keep(
        requestId: String,
        bodyBytes: Long = 0,
        replay: () -> String?,
    ) {
        synchronized(lock) {
            bytes -= kept.remove(requestId)?.bytes ?: 0
            val size = bodyBytes.coerceAtLeast(0)
            kept[requestId] = Kept(size, replay)
            bytes += size
            while (kept.size > capacity || (bytes > maxBytes && kept.size > 1)) {
                bytes -= checkNotNull(kept.remove(kept.keys.first())).bytes
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            kept.clear()
            bytes = 0
        }
    }

    /** Sends [requestId]'s call again: null once sent, else the reason `ReplayRefused` carries. */
    fun replay(requestId: String): String? {
        val replay = synchronized(lock) { kept[requestId]?.replay } ?: return NOT_KEPT
        return try {
            replay()
        } catch (e: Exception) {
            "The app could not send it again: ${e.message ?: e::class.simpleName}"
        }
    }

    companion object {
        const val CAPACITY = 200
        const val MAX_BYTES = 32L shl 20
        const val NOT_KEPT =
            "The app no longer keeps this request: it keeps its last $CAPACITY, fewer with large bodies, for this connection only"
    }
}
