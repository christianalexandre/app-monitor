package appmonitor.desktop.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import appmonitor.desktop.domain.AnsweredDevice
import appmonitor.desktop.domain.AnsweredDeviceRepository
import appmonitor.desktop.domain.AppearanceRepository
import appmonitor.desktop.domain.McpRepository
import appmonitor.desktop.domain.ProxyRepository
import appmonitor.desktop.domain.Retention
import appmonitor.desktop.domain.RetentionRepository
import appmonitor.desktop.domain.RetentionState
import appmonitor.desktop.domain.ServiceStatus
import appmonitor.desktop.domain.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the settings show of the MCP server: its [status], the switch, and the address and [token] to paste. */
data class McpSettings(
    val status: ServiceStatus,
    val enabled: Boolean,
    val port: Int,
    val path: String,
    val token: String,
)

/** What the settings show of the proxy: its [status], the switch, and the CA file the commands name. */
data class ProxySettings(
    val status: ServiceStatus,
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val caFile: String,
)

/** [retention] null until the store's is read; [mcp], [proxy] and [theme] null when the window has none. */
data class SettingsState(
    val retention: Retention? = null,
    val retentionState: RetentionState = RetentionState(),
    val answered: List<AnsweredDevice> = emptyList(),
    val mcp: McpSettings? = null,
    val proxy: ProxySettings? = null,
    val theme: ThemeMode? = null,
)

/** Retention, the answered devices, the MCP server's and proxy's switches, the theme (ADR-0082, ADR-0083, ADR-0090, ADR-0101, ADR-0145). */
class SettingsViewModel(
    private val retention: RetentionRepository,
    private val answered: AnsweredDeviceRepository? = null,
    private val mcp: McpRepository? = null,
    private val proxy: ProxyRepository? = null,
    private val appearance: AppearanceRepository? = null,
) : ViewModel() {
    // The newest retention: the store's, or the one just chosen while its writes are on their way (a second
    // choice builds on the first, not on what the store has not echoed yet).
    private val latestRetention = MutableStateFlow<Retention?>(null)
    private val retentionWrites = Mutex()
    private var pendingWrites = 0

    init {
        viewModelScope.launch { retention.retention.collect { if (pendingWrites == 0) latestRetention.value = it } }
    }

    private val mcpSettings: Flow<McpSettings?> =
        mcp?.let { mcp ->
            combine(mcp.status, mcp.enabled, mcp.token) { status, enabled, token ->
                McpSettings(status, enabled, mcp.port, mcp.path, token)
            }
        } ?: flowOf(null)

    private val proxySettings: Flow<ProxySettings?> =
        proxy?.let { proxy ->
            combine(proxy.status, proxy.enabled) { status, enabled -> ProxySettings(status, enabled, proxy.host, proxy.port, proxy.caFile) }
        } ?: flowOf(null)

    val state: StateFlow<SettingsState> =
        combine(
            latestRetention.filterNotNull(),
            retention.state,
            answered?.answered ?: flowOf(emptyList()),
            mcpSettings,
            proxySettings,
        ) { retention, retentionState, answered, mcp, proxy -> SettingsState(retention, retentionState, answered, mcp, proxy) }
            .combine(appearance?.theme ?: flowOf(null)) { state, theme -> state.copy(theme = theme) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    /** Applies [change] to the newest retention, the one shown or chosen just before, and writes it. */
    fun changeRetention(change: (Retention) -> Retention) {
        val next = latestRetention.value?.let(change) ?: return
        latestRetention.value = next
        pendingWrites++
        viewModelScope.launch {
            try {
                retentionWrites.withLock { retention.setRetention(next) }
            } finally {
                pendingWrites--
            }
        }
    }

    /** Every pair when [device] is null. */
    fun forget(device: AnsweredDevice?) {
        val answered = answered ?: return
        viewModelScope.launch { if (device == null) answered.forgetAll() else answered.forget(device.deviceId, device.appId) }
    }

    fun setMcpEnabled(on: Boolean) {
        val mcp = mcp ?: return
        viewModelScope.launch { mcp.setEnabled(on) }
    }

    fun regenerateToken() {
        val mcp = mcp ?: return
        viewModelScope.launch { mcp.regenerate() }
    }

    fun setProxyEnabled(on: Boolean) {
        val proxy = proxy ?: return
        viewModelScope.launch { proxy.setEnabled(on) }
    }

    fun resetProxyCa() {
        val proxy = proxy ?: return
        viewModelScope.launch { proxy.resetCa() }
    }

    fun setTheme(theme: ThemeMode) {
        val appearance = appearance ?: return
        viewModelScope.launch { appearance.setTheme(theme) }
    }
}
