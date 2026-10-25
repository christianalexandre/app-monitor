package appmonitor.desktop.feature.traffic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import appmonitor.desktop.designsystem.Checkbox
import appmonitor.desktop.designsystem.CheckboxRow
import appmonitor.desktop.designsystem.Choice
import appmonitor.desktop.designsystem.DialogActions
import appmonitor.desktop.designsystem.DialogCard
import appmonitor.desktop.designsystem.DialogWidth
import appmonitor.desktop.designsystem.PaneTitle
import appmonitor.desktop.designsystem.Placeholder
import appmonitor.desktop.designsystem.Spacing
import appmonitor.desktop.designsystem.Text
import appmonitor.desktop.designsystem.TextButton
import appmonitor.desktop.designsystem.TextField
import appmonitor.desktop.designsystem.TextRole
import appmonitor.desktop.designsystem.VerticalDivider
import appmonitor.desktop.designsystem.selectableItem
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.domain.Session
import appmonitor.desktop.domain.StatusClass
import appmonitor.desktop.domain.durationOf
import appmonitor.desktop.ui.METHODS
import appmonitor.desktop.ui.formatDuration
import appmonitor.desktop.ui.statusOf
import appmonitor.desktop.ui.titleOf
import org.koin.compose.viewmodel.koinViewModel

/**
 * What the traffic panes show of other screens and open there: the server's [status], a [retentionWarning], the
 * mocks', breakpoints' and export's labels, and the buttons that leave this feature.
 */
data class TrafficLinks(
    val status: String = "",
    val retentionWarning: String? = null,
    val mocksLabel: String = "Mocks",
    val breakpointsLabel: String = "Breakpoints",
    val exportNote: String? = null,
    val onSettings: () -> Unit = {},
    val onIssues: () -> Unit = {},
    val onMocks: () -> Unit = {},
    val onBreakpoints: () -> Unit = {},
    val onBenchmarks: () -> Unit = {},
    val onExportHar: () -> Unit = {},
    val onSaveAsIssue: () -> Unit = {},
    val onCompareRuns: () -> Unit = {},
    val onMock: (HttpExchange) -> Unit = {},
)

/** The live list's or a stored run's sessions, requests and request, and the runs dialog (SDD 09). */
@Composable
fun TrafficScreen(
    links: TrafficLinks,
    viewModel: TrafficViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var runsOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            DeviceList(state, links, Modifier.width(240.dp).fillMaxHeight(), onRuns = { runsOpen = true }, viewModel::selectSession)
            VerticalDivider()
            RequestList(state, links, viewModel, Modifier.weight(1f).fillMaxHeight())
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val picked = state.detail.id
                if (picked == null) {
                    Placeholder("Select a request")
                } else {
                    // Body state (format, scroll) belongs to one exchange, even if another has the same bytes.
                    key(picked) {
                        state.detail.exchange?.let {
                            RequestDetail(
                                it,
                                onCopyCurl = { viewModel.copyCurl { command -> clipboard.setText(AnnotatedString(command)) } },
                                state.detail.copyNote,
                                onMock = { links.onMock(it) },
                                onReplay = if (state.detail.canReplay) viewModel::sendAgain else null,
                                state.detail.replayNote,
                                viewModel::showOriginal,
                                viewModel::moreFrames,
                            )
                        } ?: Placeholder("Loading…")
                    }
                }
            }
        }
        if (runsOpen) {
            RunList(
                state.runs,
                onShow = {
                    viewModel.showRun(it)
                    runsOpen = false
                },
                onRename = viewModel::rename,
                onCompare = {
                    runsOpen = false
                    links.onCompareRuns()
                },
            ) { runsOpen = false }
        }
    }
}

/**
 * The live list, "Saved", then every recording, newest first; [onShow] gets null for the live list. A blank name makes a
 * recording unnamed.
 */
@Composable
private fun RunList(
    state: RunsState,
    onShow: (Long?) -> Unit,
    onRename: (Long, String) -> Unit,
    onCompare: () -> Unit,
    onClose: () -> Unit,
) {
    val runs = state.recordings
    val shownRunId = state.shownRunId
    var editing by remember { mutableStateOf<Long?>(null) }
    var draft by remember { mutableStateOf("") }
    DialogCard(DialogWidth.Medium) {
        Text("Runs", role = TextRole.Title)
        Text(
            LIVE,
            Modifier.fillMaxWidth().selectableItem(shownRunId == null) { onShow(null) }.padding(vertical = Spacing.md),
            role = TextRole.BodyStrong,
        )
        state.saved?.let { saved ->
            Text(
                titleOf(saved),
                Modifier
                    .fillMaxWidth()
                    .selectableItem(saved.id == shownRunId) { onShow(saved.id) }
                    .padding(vertical = Spacing.md)
                    .testTag(SAVED_RUN),
                role = TextRole.BodyStrong,
            )
        }
        Text("Recordings", role = TextRole.Caption)
        if (runs.isEmpty()) Text("None yet: Record stores what the devices send until Stop", role = TextRole.Caption)
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
                        }, Modifier.testTag(SAVE_RUN_NAME))
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text(titleOf(run), role = TextRole.BodyStrong)
                            Text(spanOf(run, recording = run.id == state.recording), role = TextRole.Caption)
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
            TextButton("Close", onClick = onClose)
        }
    }
}

@Composable
private fun DeviceList(
    state: TrafficState,
    links: TrafficLinks,
    modifier: Modifier,
    onRuns: () -> Unit,
    onSelect: (Long?) -> Unit,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                "${state.runs.title} ▾",
                onClick = onRuns,
                Modifier.weight(1f).padding(horizontal = Spacing.xs).testTag(RUNS),
                singleLine = true,
            )
            TextButton("Issues", onClick = links.onIssues, Modifier.testTag(ISSUES))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(links.mocksLabel, onClick = links.onMocks, Modifier.padding(horizontal = Spacing.xs).testTag(MOCKS))
            TextButton(links.breakpointsLabel, onClick = links.onBreakpoints, Modifier.testTag(BREAKPOINTS))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PaneTitle("Devices") }
            TextButton("Settings", onClick = links.onSettings)
        }
        Text(links.status, Modifier.padding(horizontal = Spacing.lg), role = TextRole.Caption)
        state.health.lastError?.let {
            Text(
                "${state.health.notSaved} events not saved: $it",
                Modifier.padding(horizontal = Spacing.lg),
                error = true,
                role = TextRole.Caption,
            )
        }
        links.retentionWarning?.let {
            Text(it, Modifier.padding(horizontal = Spacing.lg), error = true, role = TextRole.Caption)
        }
        LazyColumn {
            item {
                Text(
                    "All devices",
                    Modifier
                        .fillMaxWidth()
                        .selectableItem(
                            state.session == null,
                        ) { onSelect(null) }
                        .padding(Spacing.lg)
                        .testTag(ALL_DEVICES),
                    role = TextRole.BodyStrong,
                )
            }
            items(state.sessions, key = { it.id }) { session ->
                val hello = session.hello
                Column(
                    Modifier.fillMaxWidth().selectableItem(session.id == state.session?.id) { onSelect(session.id) }.padding(Spacing.lg),
                ) {
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
    state: TrafficState,
    links: TrafficLinks,
    viewModel: TrafficViewModel,
    modifier: Modifier,
) {
    val exchanges = state.rows
    val marked = state.marked.keys
    val filter = state.filter
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
    LaunchedEffect(nearEnd, exchanges.size) { if (nearEnd && exchanges.isNotEmpty()) viewModel.loadMore() }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PaneTitle("Requests") }
            if (state.runs.shownRunId == null) {
                if (state.runs.recording == null) {
                    TextButton("Record", onClick = viewModel::record, Modifier.testTag(RECORD))
                } else {
                    TextButton("Stop recording", onClick = viewModel::stop, Modifier.testTag(RECORD))
                }
                TextButton(
                    if (marked.isEmpty()) "Save" else "Save ${marked.size}",
                    onClick = viewModel::save,
                    Modifier.testTag(SAVE),
                    enabled = marked.isNotEmpty() || state.row != null,
                )
                TextButton("Clear", onClick = viewModel::clear, Modifier.testTag(CLEAR))
            }
        }
        state.runs.saveNote?.let { Text(it, Modifier.padding(horizontal = Spacing.lg).testTag(SAVE_NOTE), role = TextRole.Caption) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton("Benchmarks", onClick = links.onBenchmarks, Modifier.testTag(BENCHMARKS))
            TextButton("Export HAR", onClick = links.onExportHar, Modifier.testTag(EXPORT_HAR))
            TextButton(
                if (marked.isEmpty()) "Save as issue" else "Save ${marked.size} as issue",
                onClick = links.onSaveAsIssue,
                Modifier.testTag(SAVE_AS_ISSUE),
                enabled = marked.isNotEmpty() || state.row != null,
            )
        }
        links.exportNote?.let { Text(it, Modifier.padding(horizontal = Spacing.lg), role = TextRole.Caption) }
        FilterBar(filter, viewModel::setFilter)
        if (exchanges.isEmpty()) Placeholder(if (filter == ExchangeFilter()) "No requests yet" else "No requests match")
        LazyColumn(Modifier.testTag(REQUEST_LIST), state = list) {
            items(exchanges, key = { it.id }) { exchange ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectableItem(
                            exchange.id == state.row?.id,
                        ) { viewModel.selectRequest(exchange.id) }
                        .padding(Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        exchange.id in marked,
                        onCheckedChange = { viewModel.mark(exchange, it) },
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

private fun labelOf(status: StatusClass): String = "${status.from / 100}xx"

// Rows from the end of the loaded ones at which the request list asks for the next page.
private const val LOAD_AHEAD = 50
