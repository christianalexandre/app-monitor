package appmonitor.desktop.proxy

/**
 * Whether a `CONNECT` to [host] is tunnelled untouched (ADR-0101 E.1, ADR-0194): an entry equal to it, or `*.domain`
 * for any name under `domain`, the domain itself excluded. Case and a trailing dot are ignored on both sides.
 */
internal fun passesThrough(
    host: String,
    entries: Collection<String>,
): Boolean {
    val name = host.lowercase().removeSuffix(".")
    return entries.any { raw ->
        val entry = raw.trim().lowercase().removeSuffix(".")
        when {
            entry.isEmpty() -> false
            entry.startsWith("*.") -> name.endsWith(entry.substring(1)) && name.length > entry.length - 1
            else -> name == entry.removeSurrounding("[", "]")
        }
    }
}
