package appmonitor.protocol

/** One mDNS answer for a desktop; [path] is null until its TXT record is seen. */
data class DesktopAnswer(
    val name: String,
    val host: String,
    val port: Int,
    val path: String?,
)

/**
 * The rules every client applies to mDNS answers (SDD 04): instance names match case-insensitively,
 * the first desktop answered with an address is kept and every other name ignored, and its path
 * waits for the TXT record up to [TXT_GRACE_MS]. Not thread-safe: callers feed it from one coroutine.
 */
class DesktopSelector(
    private val serviceName: String? = null,
) {
    private var kept: DesktopAnswer? = null

    /** True once a desktop is kept: the caller then waits at most [TXT_GRACE_MS] for its TXT record. */
    val hasCandidate: Boolean get() = kept != null

    /** Takes [answer]; returns the kept desktop once its path is known. */
    fun offer(answer: DesktopAnswer): DesktopAnswer? {
        if (serviceName != null && !answer.name.equals(serviceName, ignoreCase = true)) return null
        val current = kept
        if (current != null && !current.name.equals(answer.name, ignoreCase = true)) return null
        val merged = answer.copy(path = answer.path ?: current?.path)
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
