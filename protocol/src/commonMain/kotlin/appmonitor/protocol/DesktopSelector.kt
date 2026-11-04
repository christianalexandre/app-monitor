package appmonitor.protocol

/** One mDNS answer for a desktop; [path] is null until its TXT record is seen, [id] null without one in it (ADR-0168). */
data class DesktopAnswer(
    val name: String,
    val host: String,
    val port: Int,
    val path: String?,
    val id: String? = null,
)

/**
 * The rules every client applies to mDNS answers: names match case-insensitively, the first desktop with an address
 * wins, its path waits for TXT up to [TXT_GRACE_MS], [pinnedId] must match it (ADR-0168). Callers: one coroutine.
 */
class DesktopSelector(
    private val serviceName: String? = null,
    private val pinnedId: String? = null,
) {
    private var kept: DesktopAnswer? = null

    /** True once a desktop is kept: the caller then waits at most [TXT_GRACE_MS] for its TXT record. */
    val hasCandidate: Boolean get() = kept != null

    /** True when [answer]'s TXT record shows a desktop other than the pinned one (ADR-0168). */
    fun refuses(answer: DesktopAnswer): Boolean = pinnedId != null && answer.path != null && answer.id != pinnedId

    /** Takes [answer]; returns the kept desktop once its path is known. */
    fun offer(answer: DesktopAnswer): DesktopAnswer? {
        if (serviceName != null && !answer.name.equals(serviceName, ignoreCase = true)) return null
        val current = kept
        if (refuses(answer) || (pinnedId != null && answer.path == null && current == null)) return null
        if (current != null && !current.name.equals(answer.name, ignoreCase = true)) return null
        val merged = answer.copy(path = answer.path ?: current?.path, id = if (answer.path == null) current?.id else answer.id)
        kept = merged
        return merged.takeIf { it.path != null }
    }

    /** The kept desktop when the grace period ends, with [Protocol.WEBSOCKET_PATH] if no TXT came. */
    fun settle(): DesktopAnswer? = kept?.let { it.copy(path = it.path ?: Protocol.WEBSOCKET_PATH) }

    companion object {
        /** How long a kept desktop waits for a TXT record that arrives after SRV/A. */
        const val TXT_GRACE_MS: Long = 500
    }
}
