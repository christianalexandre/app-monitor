package appmonitor.sdk.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** The session's last [capacity] requests a capture module can send again (ADR-0111 D.1, ADR-0122 A.1). */
internal class RecentRequests(
    private val capacity: Int = CAPACITY,
) {
    private val lock = SynchronizedObject()
    private val kept = LinkedHashMap<String, () -> String?>()

    fun keep(
        requestId: String,
        replay: () -> String?,
    ) {
        synchronized(lock) {
            kept.remove(requestId)
            kept[requestId] = replay
            if (kept.size > capacity) kept.remove(kept.keys.first())
        }
    }

    fun clear() {
        synchronized(lock) { kept.clear() }
    }

    /** Sends [requestId]'s call again: null once sent, else the reason `ReplayRefused` carries. */
    fun replay(requestId: String): String? {
        val replay = synchronized(lock) { kept[requestId] } ?: return NOT_KEPT
        return try {
            replay()
        } catch (e: Exception) {
            "The app could not send it again: ${e.message ?: e::class.simpleName}"
        }
    }

    companion object {
        const val CAPACITY = 200
        const val NOT_KEPT = "The app no longer keeps this request: it keeps its last $CAPACITY, for this connection only"
    }
}
