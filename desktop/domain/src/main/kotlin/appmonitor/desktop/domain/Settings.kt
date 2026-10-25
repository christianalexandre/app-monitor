package appmonitor.desktop.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** How long and how much the store keeps (ADR-0082 A.1); [maxAgeDays] null keeps forever. */
data class Retention(
    val maxAgeDays: Int?,
    val maxBytes: Long,
)

/** What the last retention pass left: the file's [usedBytes], whether kept data passes the size limit, its failure. */
data class RetentionState(
    val usedBytes: Long = 0,
    val keptOverBound: Boolean = false,
    val lastError: String? = null,
)

/** The store's retention settings and what its passes left (ADR-0082). */
interface RetentionRepository {
    /** The stored settings, again after each change; starts at the current value. */
    val retention: Flow<Retention>

    /** Again after each pass; starts at the current value. */
    val state: Flow<RetentionState>

    /** Writes [retention] and runs a pass with it. */
    suspend fun setRetention(retention: Retention)
}

/** The store's `settings` keys other than retention's (SDD 09). */
interface SettingRepository {
    /** Null when unset or the store is closing. */
    suspend fun setting(key: String): String?

    suspend fun setSetting(
        key: String,
        value: String,
    )
}

/** One pair the developer approved or rejected, as stored (ADR-0083 C.1). */
data class AnsweredDevice(
    val deviceId: String,
    val appId: String,
    val approved: Boolean,
    val decidedAtMs: Long,
    val deviceName: String,
    val osVersion: String,
    val appVersion: String,
)

/** The developer's stored answers, which the settings list and forget (ADR-0083 C.1). */
interface AnsweredDeviceRepository {
    /** Every stored answer, again after each change. */
    val answered: Flow<List<AnsweredDevice>>

    /** The pair's next `Hello` is asked for again; its open sessions are closed when it was approved. */
    suspend fun forget(
        deviceId: String,
        appId: String,
    )

    suspend fun forgetAll()
}

/** What the settings show about a local server the window starts: the MCP server or the proxy (SDD 10, SDD 11). */
sealed interface ServiceStatus {
    data object Off : ServiceStatus

    data object Starting : ServiceStatus

    data class Running(
        val port: Int,
    ) : ServiceStatus

    data class PortTaken(
        val port: Int,
    ) : ServiceStatus

    data class Failed(
        val message: String,
    ) : ServiceStatus
}

/** The MCP server's switch, state and token (ADR-0090). */
interface McpRepository {
    val status: StateFlow<ServiceStatus>
    val enabled: StateFlow<Boolean>
    val token: StateFlow<String>

    /** The port it listens on once running. */
    val port: Int

    /** The URL path clients post to. */
    val path: String

    /** Persists the switch, then starts or stops the server. */
    suspend fun setEnabled(on: Boolean)

    /** A new token at once; clients with the old one get `401`. */
    suspend fun regenerate()
}

/** The proxy's switch, state and CA (ADR-0101). */
interface ProxyRepository {
    val status: StateFlow<ServiceStatus>
    val enabled: StateFlow<Boolean>

    /** The port it listens on once running, on [host]. */
    val port: Int
    val host: String

    /** Where the CA certificate is once the proxy started. */
    val caFile: String

    /** Persists the switch, then starts or stops the proxy. */
    suspend fun setEnabled(on: Boolean)

    /** Deletes the CA; a running proxy restarts with a new one. */
    suspend fun resetCa()
}

/** Which theme the window paints; [System] follows the OS. */
enum class ThemeMode {
    System,
    Light,
    Dark,
}

/** The theme the settings choose, kept across launches. */
interface AppearanceRepository {
    val theme: StateFlow<ThemeMode>

    /** In memory at once, in the store through its queue. */
    suspend fun setTheme(theme: ThemeMode)
}
