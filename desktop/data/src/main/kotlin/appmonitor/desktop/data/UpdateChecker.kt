package appmonitor.desktop.data

import appmonitor.desktop.domain.AvailableUpdate
import appmonitor.desktop.domain.SettingRepository
import appmonitor.desktop.domain.UpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** The newest published release, or null when it cannot be read. */
fun interface LatestRelease {
    suspend fun latest(): AvailableUpdate?
}

/**
 * Checks [latest] when [scope] starts and every [period] while the switch, kept under [UPDATES_KEY] and on unless `false`,
 * is on (ADR-0228). A failed check keeps what the last one found.
 */
class UpdateChecker(
    private val settings: SettingRepository,
    private val current: String,
    private val latest: LatestRelease,
    scope: CoroutineScope,
    private val period: Duration = 24.hours,
) : UpdateRepository {
    override val checking = MutableStateFlow(true)
    override val available: StateFlow<AvailableUpdate?> get() = found
    private val found = MutableStateFlow<AvailableUpdate?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            checking.value = settings.setting(UPDATES_KEY) != "false"
            while (true) {
                if (checking.value) {
                    val release = runCatching { latest.latest() }.getOrNull()
                    if (release != null && checking.value) found.value = release.takeIf { isNewer(it.version, current) }
                }
                withTimeoutOrNull(period) { wake.receive() }
            }
        }
    }

    override suspend fun setChecking(on: Boolean) {
        settings.setSetting(UPDATES_KEY, on.toString())
        checking.value = on
        if (on) wake.trySend(Unit) else found.value = null
    }

    companion object {
        const val UPDATES_KEY = "updates.check"
    }
}

/**
 * Whether [latest] (`X.Y.Z`, an optional `v`) comes after [current]; a suffixed current (`1.0.0-SNAPSHOT`) comes before its
 * release. A version that is not numbers is never newer, so a `dev` build or an odd tag shows nothing.
 */
fun isNewer(
    latest: String,
    current: String,
): Boolean {
    val next = numbersOf(latest.removePrefix("v")) ?: return false
    val base = current.substringBefore('-')
    val now = numbersOf(base) ?: return false
    for (i in 0 until maxOf(next.size, now.size)) {
        val a = next.getOrElse(i) { 0 }
        val b = now.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return current != base
}

private fun numbersOf(version: String): List<Int>? =
    version.split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.size in 1..3 && !version.contains('-') }
