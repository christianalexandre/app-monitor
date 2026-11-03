package appmonitor.desktop.server

/**
 * Per session of the latest ones, the frames read and the frames lost so far, for the next session's
 * `DeliveryReport`. A session's lost count is pending until its previous session closes (ADR-0166, ADR-0172).
 */
internal class LostLedger(
    private val closedKept: Int = 1024,
) {
    private class Entry(
        val onSettled: (Long?) -> Unit,
    ) {
        var reported = false
        var written = 0L
        var read: Long? = null
        var settled = false
        var lost: Long? = null
        var next: Entry? = null
    }

    // An open session is never evicted: its next one still reads it.
    private val entries =
        object : LinkedHashMap<String, Entry>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > closedKept && eldest.value.read != null
        }

    /** [onSettled] runs under this ledger's lock when a lost count pending at the session's report settles. */
    @Synchronized
    fun opened(
        sessionId: String,
        onSettled: (Long?) -> Unit,
    ) {
        entries[sessionId] = Entry(onSettled)
    }

    /** The first report of a session names its previous one. [publish] gets the lost count known now, under this ledger's lock. */
    @Synchronized
    fun reported(
        sessionId: String,
        previousId: String,
        written: Long,
        publish: (Long?) -> Unit,
    ) {
        val entry = entries.getValue(sessionId)
        if (!entry.reported) {
            entry.reported = true
            entry.written = written
            val previous = entries[previousId]
            when {
                previousId.isEmpty() -> entry.settle(0)
                previous == null -> entry.settle(null)
                previous.settled && previous.read != null -> entry.settleAfter(previous)
                else -> previous.next = entry
            }
        }
        publish(entry.lost)
    }

    /** An SDK without reports never settles on its own, so its next session's lost count is unknown. */
    @Synchronized
    fun closed(
        sessionId: String,
        frames: Long,
    ) {
        val entry = entries.getValue(sessionId)
        entry.read = frames
        if (!entry.reported) entry.settle(null)
        settleNext(entry)
    }

    private fun Entry.settle(value: Long?) {
        settled = true
        lost = value
    }

    private fun Entry.settleAfter(previous: Entry) = settle(previous.lost?.plus((written - previous.read!!).coerceAtLeast(0)))

    private fun settleNext(entry: Entry) {
        val next = entry.next
        if (next != null && entry.settled && entry.read != null) {
            next.settleAfter(entry)
            next.onSettled(next.lost)
            settleNext(next)
        }
    }
}
