package appmonitor.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import appmonitor.desktop.app.store.HeldCalls
import appmonitor.desktop.app.store.McpStatus
import appmonitor.desktop.app.store.ProxyStatus
import appmonitor.desktop.app.store.ReplayKey
import appmonitor.desktop.app.store.ReplaySent
import appmonitor.desktop.app.store.Replays
import appmonitor.desktop.data.runRepository
import appmonitor.desktop.data.toStored
import appmonitor.desktop.data.trafficRepository
import appmonitor.desktop.designsystem.AppMonitorTheme
import appmonitor.desktop.designsystem.AppSurface
import appmonitor.desktop.designsystem.Checkbox
import appmonitor.desktop.designsystem.CheckboxRow
import appmonitor.desktop.designsystem.Choice
import appmonitor.desktop.designsystem.DialogActions
import appmonitor.desktop.designsystem.DialogCard
import appmonitor.desktop.designsystem.DialogWidth
import appmonitor.desktop.designsystem.Divider
import appmonitor.desktop.designsystem.PaneTitle
import appmonitor.desktop.designsystem.Placeholder
import appmonitor.desktop.designsystem.Section
import appmonitor.desktop.designsystem.Spacing
import appmonitor.desktop.designsystem.Text
import appmonitor.desktop.designsystem.TextButton
import appmonitor.desktop.designsystem.TextField
import appmonitor.desktop.designsystem.TextRole
import appmonitor.desktop.designsystem.VerticalDivider
import appmonitor.desktop.designsystem.selectableItem
import appmonitor.desktop.domain.CapturedBody
import appmonitor.desktop.domain.CapturedFrame
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.domain.Run
import appmonitor.desktop.domain.RunRepository
import appmonitor.desktop.domain.Session
import appmonitor.desktop.domain.StatusClass
import appmonitor.desktop.domain.StoreHealth
import appmonitor.desktop.domain.TrafficRepository
import appmonitor.desktop.domain.durationOf
import appmonitor.desktop.mcp.AskedReplay
import appmonitor.desktop.mcp.McpServer
import appmonitor.desktop.proxy.ProxyServer
import appmonitor.desktop.server.DeviceKey
import appmonitor.desktop.server.PendingDevice
import appmonitor.desktop.storage.RetentionSettings
import appmonitor.desktop.storage.RetentionStatus
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StoredApproval
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Header
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The shown run's sessions, requests and request, under the device to approve, the runs or the settings ([mcp] when
 * given; [onForget] gets null for every pair). Every pane reads [traffic], [runRepository] or [storage] and follows
 * their commits (SDD 09).
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
    val currentRun by runRepository.currentRun.collectAsState()
    val runs by remember(runRepository) { runRepository.observeRuns() }.collectAsState(emptyList())
    var shown by remember { mutableStateOf<ShownRun>(ShownRun.Current) }
    val shownRunId =
        when (val choice = shown) {
            ShownRun.Current -> currentRun
            ShownRun.All -> null
            is ShownRun.Earlier -> choice.id
        }
    val sessions by remember(traffic, shownRunId) { traffic.observeSessions(shownRunId) }.collectAsState(emptyList())
    val health by remember(traffic) { traffic.health }.collectAsState(StoreHealth())
    val retention by storage.retention.collectAsState()
    val retentionStatus by storage.retentionStatus.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedSession by remember { mutableStateOf<Long?>(null) }
    var selectedRequest by remember { mutableStateOf<Long?>(null) }
    val session = sessions.firstOrNull { it.id == selectedSession } ?: sessions.firstOrNull()
    var filter by remember { mutableStateOf(ExchangeFilter()) }
    val pages = key(session?.id, filter) { rememberExchangePages(traffic, session?.id, filter) }
    val row = pages.rows.firstOrNull { it.id == selectedRequest && it.sessionId == session?.id }
    var settingsOpen by remember { mutableStateOf(false) }
    var runsOpen by remember { mutableStateOf(false) }
    var issuesOpen by remember { mutableStateOf(false) }
    // Null: closed; a draft opens the editor on it.
    var mocksOpen by remember { mutableStateOf<MockDraft?>(null) }
    var mocksShown by remember { mutableStateOf(false) }
    val mocks by remember(storage) { storage.observeMocks() }.collectAsState(emptyList())
    var breakpointsOpen by remember { mutableStateOf(false) }
    val held by remember(heldCalls) { heldCalls?.held ?: MutableStateFlow(emptyList()) }.collectAsState()
    val askedReplays by remember(replays) { replays?.asked ?: MutableStateFlow(emptyList()) }.collectAsState()
    val everySession by remember(traffic) { traffic.observeSessions(null) }.collectAsState(emptyList())
    val appIds = remember(everySession) { everySession.map { it.hello.app_id }.distinct().sorted() }
    var benchmarksOpen by remember { mutableStateOf(false) }
    var diffOpen by remember { mutableStateOf(false) }
    // Exchanges ticked for "Save as issue", from any session or run (ADR-0095 A.1).
    var marked by remember { mutableStateOf(emptyMap<Long, Exchange>()) }
    var saving by remember { mutableStateOf<Map<Long, Exchange>?>(null) }
    var exportNote by remember { mutableStateOf<String?>(null) }
    val runTitle = if (shownRunId == null) "All runs" else runs.firstOrNull { it.id == shownRunId }?.let(::titleOf) ?: ""

    AppMonitorTheme {
        AppSurface {
            Row(Modifier.fillMaxSize()) {
                DeviceList(
                    sessions,
                    session,
                    status,
                    health,
                    retentionStatus,
                    Modifier.width(240.dp).fillMaxHeight(),
                    onSettings = { settingsOpen = true },
                    runTitle = runTitle,
                    onRuns = { runsOpen = true },
                    onIssues = { issuesOpen = true },
                    mocksLabel = mocksLabel(mocks.count { it.pending }),
                    onMocks = {
                        mocksOpen = null
                        mocksShown = true
                    },
                    breakpointsLabel = breakpointsLabel(held.size),
                    onBreakpoints = { breakpointsOpen = true },
                ) {
                    selectedSession = it.id
                }
                VerticalDivider()
                RequestList(
                    pages,
                    row,
                    filter,
                    { filter = it },
                    Modifier.weight(1f).fillMaxHeight(),
                    marked = marked.keys,
                    onMark = { exchange, on -> marked = if (on) marked + (exchange.id to exchange) else marked - exchange.id },
                    onSaveAsIssue = { saving = marked.ifEmpty { row?.let { mapOf(it.id to it) }.orEmpty() } },
                    onBenchmarks = { benchmarksOpen = true },
                    onExportHar = {
                        pickHarFile(harFileName(shownRunId))?.let { file ->
                            exportNote = "Exporting…"
                            scope.launch { exportNote = exportHar(storage, filter.copy(runId = shownRunId).toStored(), file) }
                        }
                    },
                    exportNote = exportNote,
                ) {
                    selectedRequest = it.id
                }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (row == null) {
                        Placeholder("Select a request")
                    } else {
                        // Body state (format, scroll) belongs to one exchange, even if another has the same bytes.
                        key(row.id) {
                            StoredRequestDetail(
                                storage,
                                traffic,
                                row,
                                replays?.let { ReplayTarget(it, session?.serverSessionId.orEmpty()) },
                                onShowOriginal = { id ->
                                    filter = ExchangeFilter()
                                    selectedRequest = id
                                },
                            ) { exchange ->
                                mocksOpen = mockDraftOf(exchange, session?.hello?.app_id.orEmpty())
                                mocksShown = true
                            }
                        }
                    }
                }
            }
            if (settingsOpen) {
                Settings(
                    retention,
                    retentionStatus,
                    onRetention = { scope.launch { storage.setRetention(it) } },
                    onClearAll = {
                        shown = ShownRun.Current
                        selectedSession = null
                        selectedRequest = null
                        marked = emptyMap()
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
            if (runsOpen) {
                RunList(
                    runs,
                    currentRun,
                    shownRunId,
                    onShow = {
                        shown =
                            if (it == null) {
                                ShownRun.All
                            } else if (it == currentRun) {
                                ShownRun.Current
                            } else {
                                ShownRun.Earlier(it)
                            }
                        runsOpen = false
                    },
                    onNewRun = {
                        scope.launch { runRepository.newRun() }
                        shown = ShownRun.Current
                        runsOpen = false
                    },
                    onRename = { id, name -> scope.launch { runRepository.rename(id, name) } },
                    onCompare = {
                        runsOpen = false
                        diffOpen = true
                    },
                ) { runsOpen = false }
            }
            saving?.let { exchanges ->
                SaveIssueDialog(
                    storage,
                    exchanges.keys,
                    defaultTitle = exchanges.values.minBy { it.id }.let { "${it.method ?: "—"} ${it.url ?: it.requestId}" },
                    onSaved = {
                        marked = emptyMap()
                        saving = null
                        issuesOpen = true
                    },
                ) { saving = null }
            }
            if (issuesOpen) IssueList(storage) { issuesOpen = false }
            if (mocksShown) {
                MockList(storage, appIds, mocksOpen) {
                    mocksShown = false
                    mocksOpen = null
                }
            }
            if (breakpointsOpen) BreakpointList(storage, appIds) { breakpointsOpen = false }
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
                RunDiff(storage, runs, filter.toStored(), scope) { diffOpen = false }
            }
            if (heldCalls != null) {
                // A newer hit under the same request and stage is a new form, not the old one's edits.
                held.firstOrNull()?.let { call -> key(call) { HeldCallDialog(call, held.size - 1, heldCalls) } }
            }
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

/** The first rows of a session's requests [filter] matches; [loadMore] asks for one more page once the last one is full. */
private class ExchangePages(
    val rows: List<Exchange>,
    val loadMore: () -> Unit,
)

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun rememberExchangePages(
    traffic: TrafficRepository,
    sessionId: Long?,
    filter: ExchangeFilter,
): ExchangePages {
    val limit = remember(sessionId, filter) { MutableStateFlow(TrafficRepository.PAGE_SIZE) }
    val rows by remember(traffic, sessionId, filter) {
        if (sessionId == null) {
            flowOf(emptyList())
        } else {
            limit.flatMapLatest { traffic.observeExchanges(filter.copy(sessionId = sessionId), it) }
        }
    }.collectAsState(emptyList())
    return ExchangePages(rows) { if (rows.size >= limit.value) limit.value += TrafficRepository.PAGE_SIZE }
}

/** Reads one exchange, its bodies and its first frames again whenever its [row] changes. */
@Composable
private fun StoredRequestDetail(
    storage: Storage,
    traffic: TrafficRepository,
    row: Exchange,
    replay: ReplayTarget?,
    onShowOriginal: (Long) -> Unit,
    onMock: (HttpExchange) -> Unit,
) {
    var frameLimit by remember { mutableStateOf(TrafficRepository.PAGE_SIZE) }
    val exchange by produceState<HttpExchange?>(null, row, frameLimit) { value = traffic.exchange(row.id, frameLimit) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var copyNote by remember { mutableStateOf<String?>(null) }
    val onCopyCurl: () -> Unit = {
        scope.launch {
            copyNote =
                curlOf(storage, row.id).fold(
                    { command ->
                        clipboard.setText(AnnotatedString(command))
                        "Copied"
                    },
                    { it.message },
                )
        }
    }
    val refused by remember(replay) { replay?.replays?.refused ?: MutableStateFlow(emptyMap()) }.collectAsState()
    var sent by remember { mutableStateOf<ReplaySent?>(null) }
    val replayNote =
        refused[ReplayKey(replay?.sessionId.orEmpty(), row.requestId)]?.let { "The app did not send it again: $it" }
            ?: sent?.let(::noteOf)
    val onReplay = replay?.let { { sent = it.replays.send(it.sessionId, row.requestId) } }
    exchange?.let {
        RequestDetail(it, onCopyCurl, copyNote, { onMock(it) }, onReplay, replayNote, onShowOriginal) {
            frameLimit +=
                TrafficRepository.PAGE_SIZE
        }
    } ?: Placeholder("Loading…")
}

/** Where "Send again" goes: [sessionId] is the server's id of the shown session. */
private class ReplayTarget(
    val replays: Replays,
    val sessionId: String,
)

private fun noteOf(sent: ReplaySent): String =
    when (sent) {
        ReplaySent.SENT -> "Sent to the app; its call shows as a replay"
        ReplaySent.SESSION_ENDED -> "Not sent: the app's session ended, and a replay goes through the app"
        ReplaySent.NOT_TAKEN -> "Not sent: this app's SDK does not take replays"
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

/** Which run the window shows (ADR-0084 C.1); [Current] follows "New run". */
private sealed interface ShownRun {
    data object Current : ShownRun

    data object All : ShownRun

    data class Earlier(
        val id: Long,
    ) : ShownRun
}

/** Every run, newest first, and "All runs"; [onShow] gets null for all of them. A blank name makes a run unnamed. */
@Composable
private fun RunList(
    runs: List<Run>,
    currentRun: Long,
    shownRunId: Long?,
    onShow: (Long?) -> Unit,
    onNewRun: () -> Unit,
    onRename: (Long, String) -> Unit,
    onCompare: () -> Unit,
    onClose: () -> Unit,
) {
    var editing by remember { mutableStateOf<Long?>(null) }
    var draft by remember { mutableStateOf("") }
    DialogCard(DialogWidth.Medium) {
        Text("Runs", role = TextRole.Title)
        Text(
            "All runs",
            Modifier.fillMaxWidth().selectableItem(shownRunId == null) { onShow(null) }.padding(vertical = Spacing.md),
            role = TextRole.BodyStrong,
        )
        LazyColumn(Modifier.heightIn(max = 240.dp)) {
            items(runs, key = { it.id }) { run ->
                Row(
                    Modifier.fillMaxWidth().selectableItem(run.id == shownRunId) { onShow(run.id) }.padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (run.id == editing) {
                        TextField(draft, { draft = it }, Modifier.weight(1f).testTag(RUN_NAME), singleLine = true)
                        TextButton("Save", onClick = {
                            onRename(run.id, draft)
                            editing = null
                        })
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text(titleOf(run), role = TextRole.BodyStrong)
                            Text(spanOf(run, current = run.id == currentRun), role = TextRole.Caption)
                        }
                        TextButton("Rename", onClick = {
                            draft = run.name.orEmpty()
                            editing = run.id
                        })
                    }
                }
            }
        }
        DialogActions {
            TextButton("Compare runs", onClick = onCompare, Modifier.testTag(COMPARE_RUNS), enabled = runs.size > 1)
            TextButton("New run", onClick = onNewRun)
            TextButton("Close", onClick = onClose)
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

@Composable
private fun DeviceList(
    sessions: List<Session>,
    selected: Session?,
    status: String,
    health: StoreHealth,
    retentionStatus: RetentionStatus,
    modifier: Modifier,
    onSettings: () -> Unit,
    runTitle: String,
    onRuns: () -> Unit,
    onIssues: () -> Unit,
    mocksLabel: String,
    onMocks: () -> Unit,
    breakpointsLabel: String,
    onBreakpoints: () -> Unit,
    onSelect: (Session) -> Unit,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                "$runTitle ▾",
                onClick = onRuns,
                Modifier.weight(1f).padding(horizontal = Spacing.xs).testTag(RUNS),
                singleLine = true,
            )
            TextButton("Issues", onClick = onIssues, Modifier.testTag(ISSUES))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(mocksLabel, onClick = onMocks, Modifier.padding(horizontal = Spacing.xs).testTag(MOCKS))
            TextButton(breakpointsLabel, onClick = onBreakpoints, Modifier.testTag(BREAKPOINTS))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PaneTitle("Devices") }
            TextButton("Settings", onClick = onSettings)
        }
        Text(status, Modifier.padding(horizontal = Spacing.lg), role = TextRole.Caption)
        health.lastError?.let {
            Text(
                "${health.notSaved} events not saved: $it",
                Modifier.padding(horizontal = Spacing.lg),
                error = true,
                role = TextRole.Caption,
            )
        }
        retentionWarning(retentionStatus)?.let {
            Text(it, Modifier.padding(horizontal = Spacing.lg), error = true, role = TextRole.Caption)
        }
        LazyColumn {
            items(sessions, key = { it.id }) { session ->
                val hello = session.hello
                Column(Modifier.fillMaxWidth().selectableItem(session.id == selected?.id) { onSelect(session) }.padding(Spacing.lg)) {
                    Text(hello.device_name, role = TextRole.BodyStrong)
                    Text("${hello.app_id} ${hello.app_version}", role = TextRole.Caption)
                    Text(
                        "${hello.os_version} · ${if (session.disconnectedAtMs == null) "connected" else "disconnected"}",
                        role = TextRole.Caption,
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestList(
    pages: ExchangePages,
    selected: Exchange?,
    filter: ExchangeFilter,
    onFilter: (ExchangeFilter) -> Unit,
    modifier: Modifier,
    marked: Set<Long>,
    onMark: (Exchange, Boolean) -> Unit,
    onSaveAsIssue: () -> Unit,
    onBenchmarks: () -> Unit,
    onExportHar: () -> Unit,
    exportNote: String?,
    onSelect: (Exchange) -> Unit,
) {
    val exchanges = pages.rows
    val list = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last =
                list.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: -1
            last >= list.layoutInfo.totalItemsCount - LOAD_AHEAD
        }
    }
    LaunchedEffect(nearEnd, exchanges.size) { if (nearEnd && exchanges.isNotEmpty()) pages.loadMore() }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PaneTitle("Requests") }
            TextButton("Benchmarks", onClick = onBenchmarks, Modifier.testTag(BENCHMARKS))
            TextButton("Export HAR", onClick = onExportHar, Modifier.testTag(EXPORT_HAR))
            TextButton(
                if (marked.isEmpty()) "Save as issue" else "Save ${marked.size} as issue",
                onClick = onSaveAsIssue,
                Modifier.testTag(SAVE_AS_ISSUE),
                enabled = marked.isNotEmpty() || selected != null,
            )
        }
        exportNote?.let { Text(it, Modifier.padding(horizontal = Spacing.lg), role = TextRole.Caption) }
        FilterBar(filter, onFilter)
        if (exchanges.isEmpty()) Placeholder(if (filter == ExchangeFilter()) "No requests yet" else "No requests match")
        LazyColumn(Modifier.testTag(REQUEST_LIST), state = list) {
            items(exchanges, key = { it.id }) { exchange ->
                Row(
                    Modifier.fillMaxWidth().selectableItem(exchange.id == selected?.id) { onSelect(exchange) }.padding(Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        exchange.id in marked,
                        onCheckedChange = { onMark(exchange, it) },
                        Modifier.size(20.dp).testTag(MARK + exchange.requestId),
                    )
                    Text(exchange.method ?: "—", Modifier.width(96.dp), role = TextRole.BodyStrong, maxLines = 1)
                    Text(
                        exchange.url ?: exchange.requestId,
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (exchange.mockRuleId != null) {
                        Text("mocked", Modifier.testTag(MOCKED + exchange.requestId), role = TextRole.Caption)
                    }
                    if (exchange.replayOf != null) {
                        Text("replay", Modifier.testTag(REPLAYED + exchange.requestId), role = TextRole.Caption)
                    }
                    Text(statusOf(exchange), Modifier.width(56.dp))
                    Text(durationOf(exchange.startedAtMs, exchange.endedAtMs)?.let(::formatDuration) ?: "", Modifier.width(72.dp))
                }
            }
        }
    }
}

/** Text in the URL, method, status class and failed only; the device and app are the session picked on the left. */
@Composable
private fun FilterBar(
    filter: ExchangeFilter,
    onFilter: (ExchangeFilter) -> Unit,
) {
    Column(Modifier.padding(horizontal = Spacing.lg)) {
        TextField(
            filter.text.orEmpty(),
            { onFilter(filter.copy(text = it.ifEmpty { null })) },
            Modifier.fillMaxWidth().testTag(SEARCH),
            placeholder = "Search URL",
            singleLine = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Choice("Method", filter.method, METHODS, { it }) { onFilter(filter.copy(method = it)) }
            Choice("Status", filter.statusClass, StatusClass.entries, ::labelOf) { onFilter(filter.copy(statusClass = it)) }
            CheckboxRow("Failed only", filter.failedOnly, { onFilter(filter.copy(failedOnly = it)) }, Modifier.padding(end = Spacing.md))
        }
    }
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

internal val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

private fun labelOf(status: StatusClass): String = "${status.from / 100}xx"

@Composable
private fun RequestDetail(
    exchange: HttpExchange,
    onCopyCurl: () -> Unit,
    copyNote: String?,
    onMock: () -> Unit,
    onReplay: (() -> Unit)?,
    replayNote: String?,
    onShowOriginal: (Long) -> Unit,
    onMoreFrames: () -> Unit,
) {
    val requestBody = rememberBodyView(exchange.requestHeaders, exchange.requestBody)
    val responseBody = rememberBodyView(exchange.responseHeaders, exchange.responseBody)
    var openFrame by remember { mutableStateOf<Int?>(null) }
    val frameBody = openFrame?.let { exchange.frames.getOrNull(it) }?.let { key(openFrame) { rememberBodyView(emptyList(), it.payload) } }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize().padding(Spacing.lg)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${exchange.method ?: "—"} ${exchange.url ?: exchange.requestId}",
                        Modifier.weight(1f),
                        role = TextRole.BodyStrong,
                    )
                    copyNote?.let { Text(it, role = TextRole.Caption) }
                    if (exchange.connection == null) {
                        if (exchange.url?.startsWith("ws") !=
                            true
                        ) {
                            TextButton("Mock this", onClick = onMock, Modifier.testTag(MOCK_THIS))
                            if (onReplay != null && exchange.captureLayer != CaptureLayer.CAPTURE_LAYER_PROXY) {
                                TextButton(replayLabelOf(exchange.method), onClick = onReplay, Modifier.testTag(SEND_AGAIN))
                            }
                        }
                        TextButton("Copy as cURL", onClick = onCopyCurl, Modifier.testTag(COPY_CURL))
                    }
                }
                Text(
                    listOfNotNull(
                        "Status ${statusOf(exchange)}",
                        exchange.durationMs?.let { "Duration ${formatDuration(it)}" },
                        layerOf(exchange.captureLayer),
                        exchange.mockRuleId?.let { mockedBy(exchange) },
                    ).joinToString(" · "),
                    role = TextRole.Caption,
                )
                exchange.error?.let { Text(it, error = true) }
                replayNote?.let { Text(it, Modifier.testTag(REPLAY_NOTE), role = TextRole.Caption) }
                exchange.replayOf?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val original = exchange.replayOfId
                        Text(
                            if (original != null) "Replay of an earlier request" else "Replay of a request no longer stored",
                            role = TextRole.Caption,
                        )
                        if (original != null) {
                            TextButton("Show original", onClick = { onShowOriginal(original) }, Modifier.testTag(SHOW_ORIGINAL))
                        }
                    }
                }
            }
            exchange.connection?.let { bytes ->
                item {
                    Section("Connection") {
                        Text("Sent ${countOf(bytes.sent)} · Received ${countOf(bytes.received)}", Modifier.testTag(CONNECTION_BYTES))
                        Text("Metadata only: no headers or body are captured at this layer", role = TextRole.Caption)
                    }
                }
                return@LazyColumn
            }
            item { Section("Request headers") { Headers(exchange.requestHeaders) } }
            item { Section("Request body") {} }
            body(requestBody)
            item { Section("Response headers") { Headers(exchange.responseHeaders) } }
            item { Section("Response body") {} }
            body(responseBody)
            if (exchange.frameCount > 0) {
                item { Section("WebSocket frames (${exchange.frameCount})") {} }
                exchange.frames.forEachIndexed { index, frame ->
                    item {
                        FrameRow(frame, exchange.startedAtMs, open = index == openFrame) {
                            openFrame = if (index == openFrame) null else index
                        }
                    }
                    if (index == openFrame && frameBody != null) body(frameBody)
                }
                if (exchange.frames.size < exchange.frameCount) item { TextButton("Show more frames", onClick = onMoreFrames) }
            }
        }
    }
}

@Composable
private fun Headers(headers: List<Header>) {
    if (headers.isEmpty()) Text("None", role = TextRole.Caption)
    headers.forEach { Text("${it.name}: ${it.value_}", role = TextRole.Code) }
}

/** One captured body and its rendering, which is null while it runs off the UI thread (ADR-0029). */
private class BodyView(
    val captured: CapturedBody,
    val rendered: RenderedBody?,
    val choose: (BodyFormat) -> Unit,
)

@Composable
private fun rememberBodyView(
    headers: List<Header>,
    captured: CapturedBody,
): BodyView {
    val decoded = remember(captured.bytes) { DecodedBody(captured.bytes) }
    var chosen by remember(decoded) { mutableStateOf<BodyFormat?>(null) }
    val rendered by produceState<RenderedBody?>(null, decoded, chosen) {
        value = null
        if (decoded.bytes.size > 0) value = withContext(Dispatchers.Default) { renderBody(headers, decoded, chosen) }
    }
    return BodyView(captured, rendered) { chosen = it }
}

private fun LazyListScope.body(view: BodyView) {
    val captured = view.captured
    item {
        if (captured.truncated) Text(truncationNote(captured), role = TextRole.Caption)
        if (captured.bytes.size == 0 && !captured.truncated) Text("None", role = TextRole.Caption)
        if (captured.bytes.size > 0) {
            Row {
                BodyFormat.entries.forEach { option ->
                    TextButton(option.label, onClick = { view.choose(option) }, enabled = option != view.rendered?.format)
                }
            }
        }
    }
    if (captured.bytes.size == 0) return
    val chunks = view.rendered?.chunks
    if (chunks == null) {
        item { Placeholder("Rendering…") }
    } else {
        items(chunks) { Text(it, Modifier.horizontalScroll(rememberScrollState()), role = TextRole.Code, softWrap = false) }
    }
}

@Composable
private fun FrameRow(
    frame: CapturedFrame,
    startedAtMs: Long?,
    open: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().selectableItem(open, onClick).padding(vertical = Spacing.xs)) {
        Text(
            listOfNotNull(
                directionOf(frame.direction),
                frame.opcode.name.removePrefix("OPCODE_"),
                sizeOf(frame.payload),
                startedAtMs?.let { "+${formatDuration(frame.atMs - it)}" },
            ).joinToString(" · "),
            role = TextRole.Caption,
        )
        framePreview(frame)?.let { Text(it, role = TextRole.Code, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

private fun truncationNote(captured: CapturedBody): String =
    when (val size = captured.originalSize) {
        null -> "Truncated: ${captured.bytes.size} bytes shown, original size unknown"
        else -> "Truncated: ${captured.bytes.size} of $size bytes shown"
    }

private fun directionOf(direction: WebSocketFrame.Direction): String =
    when (direction) {
        WebSocketFrame.Direction.DIRECTION_SENT -> "↑ sent"
        WebSocketFrame.Direction.DIRECTION_RECEIVED -> "↓ received"
        else -> "? direction"
    }

private fun sizeOf(payload: CapturedBody): String =
    when {
        !payload.truncated -> "${payload.bytes.size} B"
        else -> "${payload.bytes.size} of ${payload.originalSize ?: "?"} B"
    }

// A close payload is a 2-byte code and a UTF-8 reason (RFC 6455); the preview decodes a bounded prefix.
private fun framePreview(frame: CapturedFrame): String? {
    val bytes = frame.payload.bytes
    return when {
        frame.opcode == WebSocketFrame.Opcode.OPCODE_CLOSE && bytes.size >= 2 -> {
            "${((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)} ${bytes.substring(2).utf8()}"
        }

        frame.opcode == WebSocketFrame.Opcode.OPCODE_TEXT -> {
            bytes
                .substring(0, minOf(bytes.size, PREVIEW_BYTES))
                .utf8()
                .lineSequence()
                .first()
        }

        else -> {
            null
        }
    }
}

private const val PREVIEW_BYTES = 512

internal const val REQUEST_LIST = "requests"

internal const val RUN_NAME = "run-name"

internal const val SEARCH = "search"

internal const val RUNS = "runs"

internal const val CLEAR_ALL = "clear-all"

internal const val CONFIRM_CLEAR = "confirm-clear"

internal const val MCP_SWITCH = "mcp-switch"

internal const val PROXY_SWITCH = "proxy-switch"

internal const val PROXY_STATUS = "proxy-status"

internal const val PROXY_RESET_CA = "proxy-reset-ca"

internal const val MCP_STATUS = "mcp-status"

// Rows from the end of the loaded ones at which the request list asks for the next page.
private const val LOAD_AHEAD = 50

private fun layerOf(layer: CaptureLayer): String =
    when (layer) {
        CaptureLayer.CAPTURE_LAYER_PLATFORM -> "Platform layer"
        CaptureLayer.CAPTURE_LAYER_ADAPTER -> "Adapter layer"
        CaptureLayer.CAPTURE_LAYER_CONNECTION -> "Connection layer"
        CaptureLayer.CAPTURE_LAYER_PROXY -> "Proxy layer"
        CaptureLayer.CAPTURE_LAYER_UNSPECIFIED -> "Unknown layer"
    }

private fun statusOf(exchange: HttpExchange): String =
    when {
        exchange.error != null -> "failed"
        exchange.connection != null -> if (exchange.endedAtMs != null) "closed" else "open"
        (exchange.grpcStatus ?: 0) != 0 -> "grpc ${exchange.grpcStatus}"
        exchange.statusCode != null -> exchange.statusCode.toString()
        else -> "…"
    }

internal fun statusOf(exchange: Exchange): String =
    when {
        exchange.failed -> "failed"
        exchange.isConnection -> if (exchange.endedAtMs != null) "closed" else "open"
        (exchange.grpcStatus ?: 0) != 0 -> "grpc ${exchange.grpcStatus}"
        exchange.statusCode != null -> exchange.statusCode.toString()
        else -> "…"
    }

// ADR-0110 E.1: the device names the rule; a deleted one is left with its id.
private fun mockedBy(exchange: HttpExchange): String =
    exchange.mockRuleName?.let { "Mocked by rule \"$it\"" } ?: "Mocked by deleted rule ${exchange.mockRuleId}"

internal const val MOCKED = "mocked-"

internal const val REPLAYED = "replayed-"

internal const val SEND_AGAIN = "send-again"

internal const val REPLAY_NOTE = "replay-note"

internal const val SHOW_ORIGINAL = "show-original"

internal const val CONFIRM_REPLAY = "confirm-replay"

internal const val REFUSE_REPLAY = "refuse-replay"

// ADR-0111: a replayed POST really runs a second time on the server.
private fun replayLabelOf(method: String?): String =
    if (method?.uppercase() in SAFE_METHODS) "Send again" else "Send again: runs ${method ?: "it"} on the server again"

private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")

private fun countOf(bytes: Long?): String = bytes?.let { "$it B" } ?: "—"

internal const val CONNECTION_BYTES = "connection-bytes"

/** A run's name, or its start time while it has none (ADR-0084 B.1). */
internal fun titleOf(run: Run): String = run.name ?: RUN_FORMAT.format(Instant.ofEpochMilli(run.startedAtMs))

private fun spanOf(
    run: Run,
    current: Boolean,
): String =
    listOfNotNull(
        RUN_FORMAT.format(Instant.ofEpochMilli(run.startedAtMs)) +
            (run.endedAtMs?.let { " – ${TIME_FORMAT.format(Instant.ofEpochMilli(it))}" } ?: ""),
        if (current) "current" else null,
    ).joinToString(" · ")

private val RUN_FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault())

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

internal val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
