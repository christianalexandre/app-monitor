package appmonitor.desktop.feature.traffic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import appmonitor.desktop.designsystem.Badge
import appmonitor.desktop.designsystem.BadgeTone
import appmonitor.desktop.designsystem.Checkbox
import appmonitor.desktop.designsystem.DialogActions
import appmonitor.desktop.designsystem.DialogCard
import appmonitor.desktop.designsystem.DialogWidth
import appmonitor.desktop.designsystem.Divider
import appmonitor.desktop.designsystem.EmptyState
import appmonitor.desktop.designsystem.MiddleEllipsisText
import appmonitor.desktop.designsystem.PaneDivider
import appmonitor.desktop.designsystem.PaneDividerWidth
import appmonitor.desktop.designsystem.PaneTitle
import appmonitor.desktop.designsystem.Placeholder
import appmonitor.desktop.designsystem.Spacing
import appmonitor.desktop.designsystem.Text
import appmonitor.desktop.designsystem.TextButton
import appmonitor.desktop.designsystem.TextField
import appmonitor.desktop.designsystem.TextRole
import appmonitor.desktop.designsystem.Toolbar
import appmonitor.desktop.designsystem.selectableItem
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.domain.Session
import appmonitor.desktop.domain.durationOf
import appmonitor.desktop.ui.formatDuration
import appmonitor.desktop.ui.statusOf
import appmonitor.desktop.ui.titleOf
import org.koin.compose.viewmodel.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

/** The live list's or a stored run's toolbar, sessions, requests and request, and the runs dialog (SDD 09). */
@Composable
fun TrafficScreen(
    links: TrafficLinks,
    viewModel: TrafficViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var runsOpen by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    var devicesShown by remember { mutableStateOf(true) }
    var devicesWidth by remember { mutableStateOf(DEVICES_WIDTH) }
    var requestsWidth by remember { mutableStateOf(REQUESTS_WIDTH) }
    val clipboard = LocalClipboardManager.current
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TrafficToolbar(
                state,
                links,
                viewModel,
                devicesShown,
                onDevices = { devicesShown = !devicesShown },
                onRuns = { runsOpen = true },
                onFilters = { filtersOpen = true },
            )
            val notes = listOfNotNull(state.runs.saveNote?.let { it to SAVE_NOTE }, links.exportNote?.let { it to EXPORT_NOTE })
            notes.forEach { (note, tag) ->
                Text(note, Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xxs).testTag(tag), role = TextRole.Caption)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val shownDevices = if (devicesShown) devicesWidth + PaneDividerWidth else 0.dp
                val maxRequests = (maxWidth - shownDevices - PaneDividerWidth - DETAIL_MIN).coerceAtLeast(REQUESTS_MIN)
                val requests = requestsWidth.coerceIn(REQUESTS_MIN, maxRequests)
                Row(Modifier.fillMaxSize()) {
                    if (devicesShown) {
                        DeviceList(state, links, viewModel, Modifier.width(devicesWidth).fillMaxHeight())
                        PaneDivider(
                            { devicesWidth = (devicesWidth + it).coerceIn(DEVICES_MIN, DEVICES_MAX) },
                            Modifier.testTag(DEVICES_DIVIDER),
                        )
                    }
                    RequestList(state, viewModel, showClock = requests >= CLOCK_FROM, Modifier.width(requests).fillMaxHeight())
                    PaneDivider({ requestsWidth = (requests + it).coerceIn(REQUESTS_MIN, maxRequests) }, Modifier.testTag(REQUESTS_DIVIDER))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val picked = state.detail.id
                        if (picked == null) {
                            EmptyState("Select a request", "Its headers, body and timing show here")
                        } else {
                            // Body state (format, scroll) belongs to one exchange, even if another has the same bytes.
                            key(picked) {
                                state.detail.exchange?.let {
                                    RequestDetail(
                                        it,
                                        state.detail,
                                        onTab = viewModel::selectTab,
                                        onCopy = { part -> viewModel.copy(part) { text -> clipboard.setText(AnnotatedString(text)) } },
                                        onMock = { links.onMock(it) },
                                        onReplay = if (state.detail.canReplay) viewModel::sendAgain else null,
                                        viewModel::showOriginal,
                                        viewModel::moreFrames,
                                    )
                                } ?: Placeholder("Loading…")
                            }
                        }
                    }
                }
            }
        }
        if (filtersOpen) {
            FilterSheet(state.filter, state.hosts, viewModel::setFilter) { filtersOpen = false }
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

/** What is shown and what it does to it: the run picker, Record, Save, Clear, search and the filter sheet (ADR-0140 G). */
@Composable
private fun TrafficToolbar(
    state: TrafficState,
    links: TrafficLinks,
    viewModel: TrafficViewModel,
    devicesShown: Boolean,
    onDevices: () -> Unit,
    onRuns: () -> Unit,
    onFilters: () -> Unit,
) {
    val marked = state.marked.keys
    val canSave = marked.isNotEmpty() || state.row != null
    Toolbar {
        TextButton(if (devicesShown) "◂ Devices" else "▸ Devices", onClick = onDevices, Modifier.testTag(DEVICES_TOGGLE))
        TextButton("${state.runs.title} ▾", onClick = onRuns, Modifier.widthIn(max = 200.dp).testTag(RUNS), singleLine = true)
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
                enabled = canSave,
            )
            TextButton("Clear", onClick = viewModel::clear, Modifier.testTag(CLEAR))
        }
        TextButton(
            if (marked.isEmpty()) "Save as issue" else "Save ${marked.size} as issue",
            onClick = links.onSaveAsIssue,
            Modifier.testTag(SAVE_AS_ISSUE),
            enabled = canSave,
        )
        TextField(
            state.filter.text.orEmpty(),
            { viewModel.setFilter(state.filter.copy(text = it.ifEmpty { null })) },
            Modifier.weight(1f).testTag(SEARCH),
            placeholder = "Search URL",
            singleLine = true,
        )
        val count = state.filter.count
        TextButton(if (count == 0) "Filters" else "Filters ($count)", onClick = onFilters, Modifier.testTag(FILTERS))
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
    viewModel: TrafficViewModel,
    modifier: Modifier,
) {
    val onSelect = viewModel::selectSession
    Column(modifier) {
        PaneTitle("Devices")
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
        LazyColumn(Modifier.weight(1f)) {
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
            if (state.sessions.isEmpty() && state.disconnected.isEmpty()) {
                item {
                    Text(
                        "A device shows here with its first request",
                        Modifier.padding(horizontal = Spacing.lg),
                        role = TextRole.Caption,
                    )
                }
            }
            items(state.sessions, key = { it.id }) { session ->
                val hello = session.hello
                Column(
                    Modifier.fillMaxWidth().selectableItem(session.id == state.session?.id) { onSelect(session.id) }.padding(Spacing.lg),
                ) {
                    Text(hello.device_name, role = TextRole.BodyStrong)
                    Text("${hello.app_id} ${hello.app_version}", role = TextRole.Caption)
                    val refused = state.refuses(session)
                    Text(
                        "${hello.os_version} · ${when {
                            refused -> "refused until Reconnect"
                            session.disconnectedAtMs == null -> "connected"
                            else -> "disconnected"
                        }}",
                        role = TextRole.Caption,
                    )
                    if (state.canDisconnect && state.runs.shownRunId == null && !refused && session.disconnectedAtMs == null) {
                        TextButton("Disconnect", onClick = { viewModel.disconnect(session) }, Modifier.testTag(DISCONNECT + session.id))
                    }
                }
            }
            if (state.disconnected.isNotEmpty()) {
                item { Text("Disconnected", Modifier.padding(Spacing.lg), role = TextRole.Caption) }
            }
            items(state.disconnected, key = { "disconnected-${it.deviceId}-${it.appId}" }) { device ->
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.hello.device_name, role = TextRole.BodyStrong)
                        Text(device.appId, role = TextRole.Caption)
                    }
                    TextButton(
                        "Reconnect",
                        onClick = { viewModel.reconnect(device) },
                        Modifier.testTag(RECONNECT + device.deviceId + "-" + device.appId),
                    )
                }
            }
        }
        Divider()
        Links(links)
    }
}

/** The screens this one opens, two to a row under the devices. */
@Composable
private fun Links(links: TrafficLinks) {
    Column(Modifier.padding(Spacing.xs)) {
        Row {
            TextButton("Issues", onClick = links.onIssues, Modifier.weight(1f).testTag(ISSUES))
            TextButton("Benchmarks", onClick = links.onBenchmarks, Modifier.weight(1f).testTag(BENCHMARKS))
        }
        Row {
            TextButton(links.mocksLabel, onClick = links.onMocks, Modifier.weight(1f).testTag(MOCKS), singleLine = true)
            TextButton(links.breakpointsLabel, onClick = links.onBreakpoints, Modifier.weight(1f).testTag(BREAKPOINTS), singleLine = true)
        }
        Row {
            TextButton("Export HAR", onClick = links.onExportHar, Modifier.weight(1f).testTag(EXPORT_HAR))
            TextButton("Settings", onClick = links.onSettings, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RequestList(
    state: TrafficState,
    viewModel: TrafficViewModel,
    showClock: Boolean,
    modifier: Modifier,
) {
    val exchanges = state.rows
    val marked = state.marked.keys
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
    Box(modifier) {
        if (exchanges.isEmpty()) {
            when {
                state.filter != ExchangeFilter() -> EmptyState("No requests match", "Change the search or the filters")
                state.runs.shownRunId != null -> EmptyState("No requests yet", "This run holds no request")
                state.runs.recording != null -> EmptyState("No requests yet", "Recording: every request that starts is stored")
                else -> EmptyState("No requests yet", "Requests of every connected device show here as they happen")
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag(REQUEST_LIST), state = list) {
            items(exchanges, key = { it.id }) { exchange ->
                RequestRow(
                    exchange,
                    selected = exchange.id == state.row?.id,
                    marked = exchange.id in marked,
                    showClock,
                    onSelect = { viewModel.selectRequest(exchange.id) },
                    onMark = { viewModel.mark(exchange, it) },
                )
            }
        }
    }
}

/** A colored status, the method, the URL cut in its middle, when it started ([showClock]) and how long it took (ADR-0140 G). */
@Composable
private fun RequestRow(
    exchange: Exchange,
    selected: Boolean,
    marked: Boolean,
    showClock: Boolean,
    onSelect: () -> Unit,
    onMark: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().selectableItem(selected, onSelect).padding(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(marked, onCheckedChange = onMark, Modifier.size(20.dp).testTag(MARK + exchange.requestId))
        Box(Modifier.width(64.dp)) { Badge(statusOf(exchange), toneOf(exchange)) }
        Text(exchange.method ?: "—", Modifier.width(56.dp), role = TextRole.SmallStrong, maxLines = 1)
        MiddleEllipsisText(exchange.url ?: exchange.requestId, Modifier.weight(1f))
        if (exchange.mockRuleId != null) Badge("mocked", BadgeTone.Info, Modifier.testTag(MOCKED + exchange.requestId))
        if (exchange.replayOf != null) Badge("replay", BadgeTone.Neutral, Modifier.testTag(REPLAYED + exchange.requestId))
        if (showClock) {
            Text(exchange.startedAtMs?.let(::formatClock) ?: "", Modifier.width(96.dp), role = TextRole.CodeCaption, maxLines = 1)
        }
        Text(
            durationOf(exchange.startedAtMs, exchange.endedAtMs)?.let(::formatDuration) ?: "",
            Modifier.width(64.dp),
            role = TextRole.Small,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

/** Green 2xx, blue 3xx, amber 4xx, red 5xx, a failure or a gRPC error; neutral while pending or for a socket. */
internal fun toneOf(exchange: Exchange): BadgeTone {
    val code = exchange.statusCode
    return when {
        exchange.failed || (exchange.grpcStatus ?: 0) != 0 -> BadgeTone.Error
        exchange.isConnection || code == null -> BadgeTone.Neutral
        code >= 500 -> BadgeTone.Error
        code >= 400 -> BadgeTone.Warning
        code >= 300 -> BadgeTone.Info
        code >= 200 -> BadgeTone.Success
        else -> BadgeTone.Neutral
    }
}

/** A start time to the millisecond, in this machine's zone, as the request rows show it. */
internal fun formatClock(ms: Long): String = CLOCK.format(Instant.ofEpochMilli(ms))

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.systemDefault())

// ADR-0151: each pane's width at launch and the bounds a drag keeps it in; the detail takes the rest.
private val DEVICES_WIDTH = 240.dp
private val DEVICES_MIN = 240.dp
private val DEVICES_MAX = 400.dp
private val REQUESTS_WIDTH = 480.dp
private val REQUESTS_MIN = 360.dp
private val DETAIL_MIN = 360.dp

// Narrower than this, a request row leaves out its start time so the URL keeps room.
private val CLOCK_FROM = 560.dp

// Rows from the end of the loaded ones at which the request list asks for the next page.
private const val LOAD_AHEAD = 50
