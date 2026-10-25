package appmonitor.desktop.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import appmonitor.desktop.app.di.appModule
import appmonitor.desktop.app.store.HeldCalls
import appmonitor.desktop.app.store.McpStatus
import appmonitor.desktop.app.store.ProxyStatus
import appmonitor.desktop.app.store.Replays
import appmonitor.desktop.data.runRepository
import appmonitor.desktop.data.toStored
import appmonitor.desktop.data.trafficRepository
import appmonitor.desktop.designsystem.AppMonitorTheme
import appmonitor.desktop.designsystem.AppSurface
import appmonitor.desktop.designsystem.CheckboxRow
import appmonitor.desktop.designsystem.Choice
import appmonitor.desktop.designsystem.DialogActions
import appmonitor.desktop.designsystem.DialogCard
import appmonitor.desktop.designsystem.DialogWidth
import appmonitor.desktop.designsystem.Divider
import appmonitor.desktop.designsystem.Spacing
import appmonitor.desktop.designsystem.Text
import appmonitor.desktop.designsystem.TextButton
import appmonitor.desktop.designsystem.TextRole
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.RunRepository
import appmonitor.desktop.domain.TrafficRepository
import appmonitor.desktop.feature.breakpoints.BreakpointList
import appmonitor.desktop.feature.breakpoints.BreakpointsViewModel
import appmonitor.desktop.feature.breakpoints.HeldCallDialog
import appmonitor.desktop.feature.breakpoints.breakpointsLabel
import appmonitor.desktop.feature.mocks.MockDraft
import appmonitor.desktop.feature.mocks.MockList
import appmonitor.desktop.feature.mocks.MocksViewModel
import appmonitor.desktop.feature.mocks.mockDraftOf
import appmonitor.desktop.feature.mocks.mocksLabel
import appmonitor.desktop.feature.traffic.TrafficLinks
import appmonitor.desktop.feature.traffic.TrafficScreen
import appmonitor.desktop.feature.traffic.TrafficViewModel
import appmonitor.desktop.feature.traffic.replayLabelOf
import appmonitor.desktop.mcp.AskedReplay
import appmonitor.desktop.mcp.McpServer
import appmonitor.desktop.proxy.ProxyServer
import appmonitor.desktop.server.DeviceKey
import appmonitor.desktop.server.PendingDevice
import appmonitor.desktop.storage.RetentionSettings
import appmonitor.desktop.storage.RetentionStatus
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StoredApproval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.KoinIsolatedContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.dsl.koinApplication
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The traffic panes under the device to approve, the runs or the settings ([mcp] when given; [onForget] gets null for
 * every pair). Every pane reads [traffic], [runRepository] or [storage] and follows their commits (SDD 09).
 */
@Composable
internal fun AppMonitorApp(
    storage: Storage,
    status: String,
    pending: List<PendingDevice> = emptyList(),
    onDecide: (PendingDevice, approved: Boolean) -> Unit = { _, _ -> },
    answered: List<StoredApproval> = emptyList(),
    onForget: (DeviceKey?) -> Unit = {},
    onClearAll: () -> Unit = {},
    mcp: McpSettings? = null,
    onMcpEnabled: (Boolean) -> Unit = {},
    onRegenerateToken: () -> Unit = {},
    proxy: ProxySettings? = null,
    onProxyEnabled: (Boolean) -> Unit = {},
    onResetProxyCa: () -> Unit = {},
    pickHarFile: (suggested: String) -> File? = ::saveHarDialog,
    heldCalls: HeldCalls? = null,
    replays: Replays? = null,
    runRepository: RunRepository = remember(storage) { storage.runRepository() },
    traffic: TrafficRepository = remember(storage) { storage.trafficRepository() },
) {
    val koin =
        remember(
            storage,
            runRepository,
            traffic,
            replays,
            heldCalls,
        ) { koinApplication { modules(appModule(storage, runRepository, traffic, replays, heldCalls)) } }
    DisposableEffect(koin) { onDispose { koin.close() } }
    // ADR-0136: the window's view models live as long as this composition.
    val owner = remember(koin) { WindowViewModels() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        KoinIsolatedContext(koin) {
            AppMonitorWindow(
                storage,
                status,
                pending,
                onDecide,
                answered,
                onForget,
                onClearAll,
                mcp,
                onMcpEnabled,
                onRegenerateToken,
                proxy,
                onProxyEnabled,
                onResetProxyCa,
                pickHarFile,
                replays,
                traffic,
            )
        }
    }
}

private class WindowViewModels : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

@Composable
private fun AppMonitorWindow(
    storage: Storage,
    status: String,
    pending: List<PendingDevice>,
    onDecide: (PendingDevice, approved: Boolean) -> Unit,
    answered: List<StoredApproval>,
    onForget: (DeviceKey?) -> Unit,
    onClearAll: () -> Unit,
    mcp: McpSettings?,
    onMcpEnabled: (Boolean) -> Unit,
    onRegenerateToken: () -> Unit,
    proxy: ProxySettings?,
    onProxyEnabled: (Boolean) -> Unit,
    onResetProxyCa: () -> Unit,
    pickHarFile: (suggested: String) -> File?,
    replays: Replays?,
    traffic: TrafficRepository,
) {
    val viewModel = koinViewModel<TrafficViewModel>()
    val state by viewModel.state.collectAsState()
    val shownRunId = state.runs.shownRunId
    val filter = state.filter
    val retention by storage.retention.collectAsState()
    val retentionStatus by storage.retentionStatus.collectAsState()
    val scope = rememberCoroutineScope()
    var settingsOpen by remember { mutableStateOf(false) }
    var issuesOpen by remember { mutableStateOf(false) }
    // Null: closed; a draft opens the editor on it.
    var mocksOpen by remember { mutableStateOf<MockDraft?>(null) }
    var mocksShown by remember { mutableStateOf(false) }
    val mocks by koinViewModel<MocksViewModel>().state.collectAsState()
    var breakpointsOpen by remember { mutableStateOf(false) }
    val breakpoints by koinViewModel<BreakpointsViewModel>().state.collectAsState()
    val held = breakpoints.held
    val askedReplays by remember(replays) { replays?.asked ?: MutableStateFlow(emptyList()) }.collectAsState()
    val everySession by remember(traffic) { traffic.observeSessions(null) }.collectAsState(emptyList())
    val appIds = remember(everySession) { everySession.map { it.hello.app_id }.distinct().sorted() }
    var benchmarksOpen by remember { mutableStateOf(false) }
    var diffOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<Map<Long, Exchange>?>(null) }
    var exportNote by remember { mutableStateOf<String?>(null) }
    val runTitle = state.runs.title

    AppMonitorTheme {
        AppSurface {
            TrafficScreen(
                TrafficLinks(
                    status = status,
                    retentionWarning = retentionWarning(retentionStatus),
                    mocksLabel = mocksLabel(mocks.pending),
                    breakpointsLabel = breakpointsLabel(held.size),
                    exportNote = exportNote,
                    onSettings = { settingsOpen = true },
                    onIssues = { issuesOpen = true },
                    onMocks = {
                        mocksOpen = null
                        mocksShown = true
                    },
                    onBreakpoints = { breakpointsOpen = true },
                    onBenchmarks = { benchmarksOpen = true },
                    onExportHar = {
                        pickHarFile(harFileName(shownRunId))?.let { file ->
                            exportNote = "Exporting…"
                            scope.launch { exportNote = exportHar(storage, filter.copy(runId = shownRunId).toStored(), file) }
                        }
                    },
                    onSaveAsIssue = { saving = viewModel.toSave() },
                    onCompareRuns = { diffOpen = true },
                    onMock = { exchange ->
                        mocksOpen =
                            mockDraftOf(
                                exchange,
                                state.session
                                    ?.hello
                                    ?.app_id
                                    .orEmpty(),
                            )
                        mocksShown = true
                    },
                ),
                viewModel,
            )
            if (settingsOpen) {
                Settings(
                    retention,
                    retentionStatus,
                    onRetention = { scope.launch { storage.setRetention(it) } },
                    onClearAll = {
                        viewModel.reset()
                        onClearAll()
                    },
                    answered,
                    onForget,
                    mcp,
                    onMcpEnabled,
                    onRegenerateToken,
                    proxy,
                    onProxyEnabled,
                    onResetProxyCa,
                ) { settingsOpen = false }
            }
            saving?.let { exchanges ->
                SaveIssueDialog(
                    storage,
                    exchanges.keys,
                    defaultTitle = exchanges.values.minBy { it.id }.let { "${it.method ?: "—"} ${it.url ?: it.requestId}" },
                    onSaved = {
                        viewModel.clearMarked()
                        saving = null
                        issuesOpen = true
                    },
                ) { saving = null }
            }
            if (issuesOpen) IssueList(storage) { issuesOpen = false }
            if (mocksShown) {
                MockList(appIds, mocksOpen, onClose = {
                    mocksShown = false
                    mocksOpen = null
                })
            }
            if (breakpointsOpen) BreakpointList(appIds, onClose = { breakpointsOpen = false })
            if (benchmarksOpen) {
                val devices = if (shownRunId == null) "Every run and device" else "Every device of $runTitle"
                val scope = devices + if (filter == ExchangeFilter()) "" else ", under the request filter"
                Benchmarks(storage, filter.copy(runId = shownRunId).toStored(), scope) { benchmarksOpen = false }
            }
            if (diffOpen) {
                val scope =
                    if (filter ==
                        ExchangeFilter()
                    ) {
                        "Every device of each run"
                    } else {
                        "Every device of each run, under the request filter"
                    }
                RunDiff(storage, state.runs.runs, filter.toStored(), scope) { diffOpen = false }
            }
            // A newer hit under the same request and stage is a new form, not the old one's edits.
            held.firstOrNull()?.let { call -> key(call) { HeldCallDialog(call, held.size - 1) } }
            if (replays != null) {
                askedReplays.firstOrNull()?.let { AskedReplayDialog(it, askedReplays.size - 1) { send -> replays.answer(it, send) } }
            }
            pending.firstOrNull()?.let { ApprovalDialog(it, waiting = pending.size - 1, onDecide) }
        }
    }
}

/**
 * What the window shows when the store did not open: no server runs behind it (SDD 09). [onClearAll] deletes the files
 * after a confirmation and returns what to tell the developer.
 */
@Composable
internal fun StorageUnavailable(
    file: String,
    error: String,
    onClearAll: () -> String = { "" },
) {
    var confirming by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf<String?>(null) }
    AppMonitorTheme {
        AppSurface {
            Column(Modifier.padding(Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("App Monitor could not open its data", role = TextRole.Title)
                Text(error, error = true)
                Text(file, role = TextRole.Code)
                Text("No device is admitted until the file opens.", role = TextRole.Caption)
                val result = cleared
                when {
                    result != null -> {
                        Text(result)
                    }

                    confirming -> {
                        Text("Delete this file and everything in it? This cannot be undone.", error = true)
                        Row {
                            TextButton("Cancel", onClick = { confirming = false })
                            TextButton("Delete everything", onClick = { cleared = onClearAll() }, Modifier.testTag(CONFIRM_CLEAR))
                        }
                    }

                    else -> {
                        TextButton("Clear all data", onClick = { confirming = true }, Modifier.testTag(CLEAR_ALL))
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalDialog(
    device: PendingDevice,
    waiting: Int,
    onDecide: (PendingDevice, approved: Boolean) -> Unit,
) {
    DialogCard(DialogWidth.Narrow) {
        Text(
            "${device.hello.device_name.ifEmpty { "Unnamed device" }} — ${device.key.appId} wants to connect",
            role = TextRole.BodyStrong,
        )
        Text(
            listOf(device.hello.os_version, device.hello.app_version, "device ${device.key.deviceId}")
                .filter { it.isNotEmpty() }
                .joinToString(" · "),
            role = TextRole.Caption,
        )
        if (waiting > 0) Text("$waiting more waiting", role = TextRole.Caption)
        DialogActions {
            TextButton("Reject", onClick = { onDecide(device, false) })
            TextButton("Approve", onClick = { onDecide(device, true) })
        }
    }
}

/** ADR-0005, ADR-0121 B.1: nothing an agent asks reaches the device before "Send again". */
@Composable
private fun AskedReplayDialog(
    replay: AskedReplay,
    waiting: Int,
    onAnswer: (send: Boolean) -> Unit,
) {
    DialogCard(DialogWidth.Narrow) {
        Text("An agent asks to send this request again", role = TextRole.BodyStrong)
        Text("${replay.method ?: "—"} ${replay.url ?: replay.requestId}")
        Text(
            "${replay.deviceName.ifEmpty { "Unnamed device" }} — ${replay.appId} · exchange ${replay.exchangeId}",
            role = TextRole.Caption,
        )
        if (waiting > 0) Text("$waiting more waiting", role = TextRole.Caption)
        DialogActions {
            TextButton("Refuse", onClick = { onAnswer(false) }, Modifier.testTag(REFUSE_REPLAY))
            TextButton(replayLabelOf(replay.method), onClick = { onAnswer(true) }, Modifier.testTag(CONFIRM_REPLAY))
        }
    }
}

/** What the settings show of the MCP server: its [status], the switch, and the address and [token] to paste. */
internal data class McpSettings(
    val status: McpStatus,
    val enabled: Boolean,
    val port: Int,
    val token: String,
)

/** The command the settings show; [McpServer.PATH] and the header are what the server checks (ADR-0090). */
internal fun claudeMcpAdd(
    port: Int,
    token: String,
): String = "claude mcp add --transport http app-monitor http://127.0.0.1:$port${McpServer.PATH} --header \"Authorization: Bearer $token\""

/**
 * Retention with "Clear all data" behind a confirmation (ADR-0082), the MCP server's switch, state and command
 * (ADR-0090), the proxy's (ADR-0101), and every answered pair with "Forget" and "Forget all" (ADR-0083 C.1).
 */
@Composable
private fun Settings(
    retention: RetentionSettings,
    status: RetentionStatus,
    onRetention: (RetentionSettings) -> Unit,
    onClearAll: () -> Unit,
    answered: List<StoredApproval>,
    onForget: (DeviceKey?) -> Unit,
    mcp: McpSettings?,
    onMcpEnabled: (Boolean) -> Unit,
    onRegenerateToken: () -> Unit,
    proxy: ProxySettings?,
    onProxyEnabled: (Boolean) -> Unit,
    onResetProxyCa: () -> Unit,
    onClose: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    DialogCard(DialogWidth.Medium, scrollable = true) {
        Text("Settings", role = TextRole.Title)
        Text("Retention", role = TextRole.Heading)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Choice("Keep", retention.maxAgeDays, AGE_CHOICES, ::ageOf, any = "Forever") {
                onRetention(retention.copy(maxAgeDays = it))
            }
            Choice("Up to", retention.maxBytes, SIZE_CHOICES, ::bytesOf, any = null) {
                if (it != null) onRetention(retention.copy(maxBytes = it))
            }
        }
        Text(
            "${bytesOf(status.usedBytes)} on disk. Named runs and the requests of issues are kept past both limits.",
            role = TextRole.Caption,
        )
        retentionWarning(
            status,
            retention,
        )?.let { Text(it, error = true, role = TextRole.Caption) }
        if (confirming) {
            Text("Delete every run, request, issue and device answer? This cannot be undone.", error = true)
            DialogActions {
                TextButton("Cancel", onClick = { confirming = false })
                TextButton("Delete everything", onClick = {
                    confirming = false
                    onClearAll()
                }, Modifier.testTag(CONFIRM_CLEAR))
            }
        } else {
            TextButton("Clear all data", onClick = { confirming = true }, Modifier.testTag(CLEAR_ALL))
        }
        if (mcp != null) {
            Divider()
            McpSection(mcp, onMcpEnabled, onRegenerateToken)
        }
        if (proxy != null) {
            Divider()
            ProxySection(proxy, onProxyEnabled, onResetProxyCa)
        }
        Divider()
        Text("Devices", role = TextRole.Heading)
        if (answered.isEmpty()) Text("No device answered yet", role = TextRole.Caption)
        LazyColumn(Modifier.heightIn(max = 240.dp)) {
            items(answered, key = { "${it.deviceId}/${it.appId}" }) { pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${pair.deviceName.ifEmpty { "Unnamed device" }} — ${pair.appId}", role = TextRole.BodyStrong)
                        Text(
                            listOf(
                                if (pair.approved) "Approved" else "Rejected",
                                DATE_FORMAT.format(Instant.ofEpochMilli(pair.decidedAtMs)),
                                pair.osVersion,
                                pair.appVersion,
                            ).filter { it.isNotEmpty() }.joinToString(" · "),
                            role = TextRole.Caption,
                        )
                    }
                    TextButton("Forget", onClick = { onForget(DeviceKey(pair.deviceId, pair.appId)) })
                }
            }
        }
        DialogActions {
            TextButton("Forget all", onClick = { onForget(null) }, enabled = answered.isNotEmpty())
            TextButton("Close", onClick = onClose)
        }
    }
}

@Composable
private fun McpSection(
    mcp: McpSettings,
    onEnabled: (Boolean) -> Unit,
    onRegenerateToken: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val command = claudeMcpAdd((mcp.status as? McpStatus.Running)?.port ?: mcp.port, mcp.token)
    Text("MCP server", role = TextRole.Heading)
    CheckboxRow("Serve the stored traffic to AI agents over MCP", mcp.enabled, onEnabled, Modifier.testTag(MCP_SWITCH))
    val (state, error) = mcpStateOf(mcp.status)
    Text(
        state,
        Modifier.testTag(MCP_STATUS),
        error = error,
        role = TextRole.Caption,
    )
    if (mcp.enabled) {
        Text("Add it to Claude Code:", role = TextRole.Caption)
        SelectionContainer {
            Text(command, role = TextRole.CodeCaption)
        }
        Row {
            TextButton("Copy command", onClick = { clipboard.setText(AnnotatedString(command)) })
            TextButton("Regenerate token", onClick = onRegenerateToken)
        }
    }
}

private fun mcpStateOf(status: McpStatus): Pair<String, Boolean> =
    when (status) {
        McpStatus.Off -> "Off" to false
        McpStatus.Starting -> "Starting…" to false
        is McpStatus.Running -> "Listening on http://127.0.0.1:${status.port}${McpServer.PATH}" to false
        is McpStatus.PortTaken -> "Port ${status.port} in use: quit what holds it, or set APPMONITOR_MCP_PORT" to true
        is McpStatus.Failed -> "Failed: ${status.message}" to true
    }

/** What the settings show of the proxy: its [status], the switch, and the CA file the commands name. */
internal data class ProxySettings(
    val status: ProxyStatus,
    val enabled: Boolean,
    val port: Int,
    val caFile: String,
)

/** The launch command of ADR-0108: gRPC-Core in the app reads `grpc_proxy`, and its TLS roots from [caFile]. */
internal fun simulatorLaunch(
    port: Int,
    caFile: String,
): String =
    "SIMCTL_CHILD_grpc_proxy=http://${ProxyServer.HOST}:$port SIMCTL_CHILD_GRPC_DEFAULT_SSL_ROOTS_FILE_PATH=\"$caFile\" " +
        "xcrun simctl launch booted <bundle id>"

internal fun curlThroughProxy(
    port: Int,
    caFile: String,
): String = "curl --proxy http://${ProxyServer.HOST}:$port --cacert \"$caFile\" https://example.com"

@Composable
private fun ProxySection(
    proxy: ProxySettings,
    onEnabled: (Boolean) -> Unit,
    onResetCa: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val port = (proxy.status as? ProxyStatus.Running)?.port ?: proxy.port
    Text("Proxy", role = TextRole.Heading)
    CheckboxRow("Record what apps send through a proxy on 127.0.0.1", proxy.enabled, onEnabled, Modifier.testTag(PROXY_SWITCH))
    val (state, error) = proxyStateOf(proxy.status)
    Text(
        state,
        Modifier.testTag(PROXY_STATUS),
        error = error,
        role = TextRole.Caption,
    )
    if (proxy.enabled) {
        Text("Launch a simulator app through it, or try it with curl:", role = TextRole.Caption)
        listOf(simulatorLaunch(port, proxy.caFile), curlThroughProxy(port, proxy.caFile)).forEach { command ->
            SelectionContainer {
                Text(command, role = TextRole.CodeCaption)
            }
        }
        Row {
            TextButton("Copy launch command", onClick = { clipboard.setText(AnnotatedString(simulatorLaunch(port, proxy.caFile))) })
            TextButton("Reset proxy CA", onClick = onResetCa, Modifier.testTag(PROXY_RESET_CA))
        }
        Text(
            "After a reset, relaunch the apps started with the old CA.",
            role = TextRole.Caption,
        )
    }
}

private fun proxyStateOf(status: ProxyStatus): Pair<String, Boolean> =
    when (status) {
        ProxyStatus.Off -> "Off" to false
        ProxyStatus.Starting -> "Starting…" to false
        is ProxyStatus.Running -> "Listening on http://${ProxyServer.HOST}:${status.port}" to false
        is ProxyStatus.PortTaken -> "Port ${status.port} in use: quit what holds it, or set APPMONITOR_PROXY_PORT" to true
        is ProxyStatus.Failed -> "Failed: ${status.message}" to true
    }

private val AGE_CHOICES = listOf(1, 3, 7, 14, 30, 90)

private val SIZE_CHOICES = listOf(100L shl 20, 500L shl 20, 1L shl 30, 2L shl 30, 5L shl 30, 10L shl 30, 20L shl 30)

private fun ageOf(days: Int): String = if (days == 1) "1 day" else "$days days"

private fun bytesOf(bytes: Long): String =
    when {
        bytes >= 1L shl 30 -> "%.1f GiB".format(Locale.ENGLISH, bytes / (1L shl 30).toDouble()).replace(".0 ", " ")
        bytes >= 1L shl 20 -> "${bytes shr 20} MiB"
        else -> "${bytes shr 10} KiB"
    }

/** Null while retention keeps the file within its bounds and its last pass did not fail. */
private fun retentionWarning(
    status: RetentionStatus,
    retention: RetentionSettings? = null,
): String? =
    when {
        status.lastError != null -> {
            "Retention failed: ${status.lastError}"
        }

        status.keptOverBound -> {
            "Named runs, issues and connected devices pass the size limit" + (retention?.let { " of ${bytesOf(it.maxBytes)}" } ?: "")
        }

        else -> {
            null
        }
    }

internal const val CLEAR_ALL = "clear-all"

internal const val CONFIRM_CLEAR = "confirm-clear"

internal const val MCP_SWITCH = "mcp-switch"

internal const val PROXY_SWITCH = "proxy-switch"

internal const val PROXY_STATUS = "proxy-status"

internal const val PROXY_RESET_CA = "proxy-reset-ca"

internal const val MCP_STATUS = "mcp-status"

internal const val CONFIRM_REPLAY = "confirm-replay"

internal const val REFUSE_REPLAY = "refuse-replay"

internal val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
