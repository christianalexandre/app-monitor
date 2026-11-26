package appmonitor.desktop.domain

import kotlinx.coroutines.flow.StateFlow

/** A release newer than the running desktop: its [version] and the page that has its packages (ADR-0228). */
data class AvailableUpdate(
    val version: String,
    val url: String,
)

/** The update notice (ADR-0228): whether the desktop reads its latest GitHub Release, and the newer one it found. */
interface UpdateRepository {
    val checking: StateFlow<Boolean>

    /** Null when the running version is the newest, the check is off, or no check has answered yet. */
    val available: StateFlow<AvailableUpdate?>

    /** Persists the switch; on, it checks at once; off, it forgets what it found. */
    suspend fun setChecking(on: Boolean)
}
