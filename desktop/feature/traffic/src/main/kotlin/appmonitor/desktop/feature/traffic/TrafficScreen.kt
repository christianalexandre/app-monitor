package appmonitor.desktop.feature.traffic

import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import appmonitor.desktop.designsystem.PaneDivider
import appmonitor.desktop.designsystem.PaneDividerWidth
import appmonitor.desktop.designsystem.PaneTitle
import appmonitor.desktop.designsystem.PathText
import appmonitor.desktop.designsystem.Placeholder
import appmonitor.desktop.designsystem.SearchField
import appmonitor.desktop.designsystem.SegmentButton
import appmonitor.desktop.designsystem.Spacing
import appmonitor.desktop.designsystem.StatusBar
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
import appmonitor.desktop.domain.appLabel
import appmonitor.desktop.domain.durationOf
import appmonitor.desktop.ui.formatDuration
import appmonitor.desktop.ui.statusOf
import appmonitor.desktop.ui.titleOf
import appmonitor.v1.Platform
import org.koin.compose.viewmodel.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

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

/** The live list's or a stored run's toolbar, sessions, requests and request, and the runs dialog. */
@Composable
fun TrafficScreen(
    links: TrafficLinks,
    viewModel: TrafficViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var runsOpen by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    // Kept while another request is picked, so the same text is looked for in each.
    var detailQuery by remember { mutableStateOf("") }
    val saved by viewModel.panes.collectAsState()
    val devicesShown = saved.devicesShown
    var devicesWidth by remember { mutableStateOf(saved.devicesWidth.dp.coerceIn(DEVICES_MIN, DEVICES_MAX)) }
    var requestsWidth by remember { mutableStateOf(saved.requestsWidth.dp.coerceAtLeast(REQUESTS_MIN)) }
    val keepWidths = {
        viewModel.setPanes(saved.copy(devicesWidth = devicesWidth.value.roundToInt(), requestsWidth = requestsWidth.value.roundToInt()))
    }
    val clipboard = LocalClipboardManager.current
    val root = remember { FocusRequester() }
    val search = remember { FocusRequester() }
    // Key events reach a node only through the focused one: the window's root holds focus while no field does.
    LaunchedEffect(Unit) { root.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                (event.type == KeyEventType.KeyDown && event.isShortcut && event.key == Key.F).also { if (it) search.requestFocus() }
            }.focusRequester(root)
            .focusable(),
    ) {
        Column(Modifier.fillMaxSize()) {
            TrafficToolbar(
                state,
                links,
                viewModel,
                devicesShown,
                onDevices = { viewModel.setPanes(saved.copy(devicesShown = !devicesShown)) },
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
                            onDragStopped = keepWidths,
                        )
                    }
                    RequestList(state, viewModel, links.onSaveAsIssue, search, Modifier.width(requests).fillMaxHeight())
                    PaneDivider(
                        { requestsWidth = (requests + it).coerceIn(REQUESTS_MIN, maxRequests) },
                        Modifier.testTag(REQUESTS_DIVIDER),
                        onDragStopped = keepWidths,
                    )
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
                                        detailQuery,
                                        { detailQuery = it },
                                        device =
                                            state.row
                                                ?.let { row -> state.sessions.firstOrNull { it.id == row.sessionId } }
                                                ?.hello
                                                ?.device_name,
                                        onSaveAsIssue = links.onSaveAsIssue,
                                    )
                                } ?: Placeholder("Loading…")
                            }
                        }
                    }
                }
            }
            TrafficStatusBar(state, links)
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
    // Marked rows have their own Save and "Save as issue" under the list.
    val unmarked = state.marked.isEmpty()
    Toolbar {
        TextButton(if (devicesShown) "◂ Devices" else "▸ Devices", onClick = onDevices, Modifier.testTag(DEVICES_TOGGLE))
        TextButton("${state.runs.title} ▾", onClick = onRuns, Modifier.widthIn(max = 200.dp).testTag(RUNS), singleLine = true)
        if (state.runs.shownRunId == null) {
            if (state.runs.recording == null) {
                TextButton("Record", onClick = viewModel::record, Modifier.testTag(RECORD))
            } else {
                TextButton("Stop recording", onClick = viewModel::stop, Modifier.testTag(RECORD))
            }
            if (unmarked) TextButton("Save", onClick = viewModel::save, Modifier.testTag(SAVE), enabled = state.row != null)
            TextButton("Clear", onClick = viewModel::clear, Modifier.testTag(CLEAR))
        }
        if (unmarked) {
            TextButton("Save as issue", onClick = links.onSaveAsIssue, Modifier.testTag(SAVE_AS_ISSUE), enabled = state.row != null)
        }
        Spacer(Modifier.weight(1f))
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
                    Text("${hello.appLabel} ${hello.app_version}", role = TextRole.Caption)
                    val refused = state.refuses(session)
                    Text(
                        "${hello.os_version} · ${when {
                            refused -> "refused until Reconnect"
                            session.disconnectedAtMs == null -> "connected"
                            else -> "disconnected"
                        }}",
                        role = TextRole.Caption,
                    )
                    session.drops?.takeIf { it.any }?.let { drops ->
                        Text(dropsLabel(drops), Modifier.testTag(DROPS + session.id), error = true, role = TextRole.Caption)
                    }
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
                        Text(device.hello.appLabel, role = TextRole.Caption)
                    }
                    TextButton(
                        "Reconnect",
                        onClick = { viewModel.reconnect(device) },
                        Modifier.testTag(RECONNECT + device.deviceId + "-" + device.appId),
                    )
                }
            }
        }
    }
}

/** The server's status and what went wrong storing, then the screens this one opens; shown whether the devices pane is or not. */
@Composable
private fun TrafficStatusBar(
    state: TrafficState,
    links: TrafficLinks,
) {
    StatusBar {
        Column(Modifier.weight(1f)) {
            Text(links.status, role = TextRole.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            state.health.lastError?.let {
                Text("${state.health.notSaved} events not saved: $it", error = true, role = TextRole.Caption, maxLines = 1)
            }
            links.retentionWarning?.let { Text(it, error = true, role = TextRole.Caption, maxLines = 1) }
        }
        TextButton("Issues", onClick = links.onIssues, Modifier.testTag(ISSUES))
        TextButton("Benchmarks", onClick = links.onBenchmarks, Modifier.testTag(BENCHMARKS))
        TextButton(links.mocksLabel, onClick = links.onMocks, Modifier.testTag(MOCKS), singleLine = true)
        TextButton(links.breakpointsLabel, onClick = links.onBreakpoints, Modifier.testTag(BREAKPOINTS), singleLine = true)
        TextButton("Export HAR", onClick = links.onExportHar, Modifier.testTag(EXPORT_HAR))
        TextButton("Settings", onClick = links.onSettings)
    }
}

@Composable
private fun RequestList(
    state: TrafficState,
    viewModel: TrafficViewModel,
    onSaveAsIssue: () -> Unit,
    search: FocusRequester,
    modifier: Modifier,
) {
    val exchanges = state.rows
    val marked = state.marked.keys
    val platforms = remember(state.sessions) { state.sessions.associate { it.id to it.hello.platform } }
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
        ListHeader(state, viewModel, search)
        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                        platforms[exchange.sessionId],
                        selected = exchange.id == state.row?.id,
                        marked = exchange.id in marked,
                        onSelect = { viewModel.selectRequest(exchange.id) },
                        onMark = { viewModel.mark(exchange, it) },
                    )
                    Divider()
                }
            }
        }
        if (marked.isNotEmpty()) {
            Divider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xxs).testTag(MARKED_BAR),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${marked.size} marked", Modifier.weight(1f), role = TextRole.Small, secondary = true)
                TextButton("Save as issue", onClick = onSaveAsIssue, Modifier.testTag(SAVE_AS_ISSUE))
                if (state.runs.shownRunId == null) TextButton("Save", onClick = viewModel::save, Modifier.testTag(SAVE))
            }
        }
    }
}

/** The URL filter (⌘F), the status classes and how many requests are listed, over the list (ADR-0203). */
@Composable
private fun ListHeader(
    state: TrafficState,
    viewModel: TrafficViewModel,
    search: FocusRequester,
) {
    val filter = state.filter
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
        SearchField(
            filter.text.orEmpty(),
            { viewModel.setFilter(filter.copy(text = it.ifEmpty { null })) },
            "Filter by URL",
            Modifier.fillMaxWidth().focusRequester(search).testTag(SEARCH),
            shortcut = SHORTCUT_PREFIX + "F",
        )
        Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            val shown = StatusChoice.of(filter)
            StatusChoice.entries.forEach { choice ->
                SegmentButton(
                    choice.label,
                    selected = choice == shown,
                    onClick = { viewModel.setFilter(choice.applyTo(filter)) },
                    Modifier.testTag(STATUS_FILTER + choice.tag),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(countLabel(state.rows.size, state.more), role = TextRole.Caption, secondary = true, maxLines = 1)
        }
    }
    Divider()
}

/**
 * Two lines (ADR-0203): the method, the path with its query and when it started; then the status badge, the host or what
 * failed, the "mocked" and "replay" badges, the device's [platform] and the duration, in the warning tone over 2 s.
 */
@Composable
private fun RequestRow(
    exchange: Exchange,
    platform: Platform?,
    selected: Boolean,
    marked: Boolean,
    onSelect: () -> Unit,
    onMark: (Boolean) -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(hover)
            .selectableItem(selected, onSelect)
            .padding(start = Spacing.xs, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            marked,
            onCheckedChange = onMark,
            Modifier.size(MARK_SIZE).alpha(if (marked || hovered) 1f else 0f).testTag(MARK + exchange.requestId),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            val url = exchange.url ?: exchange.requestId
            val parts = exchange.url?.let(::urlPartsOf) ?: UrlParts("", exchange.requestId, "")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(exchange.method ?: "—", Modifier.width(METHOD_WIDTH), role = TextRole.CodeCaption, maxLines = 1)
                PathText(parts.path, parts.query, Modifier.weight(1f), semantics = url)
                Text(exchange.startedAtMs?.let(::formatClock) ?: "", role = TextRole.CodeCaption, secondary = true, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(METHOD_WIDTH)) { Badge(statusOf(exchange), toneOf(exchange)) }
                Text(
                    exchange.error ?: exchange.host ?: parts.host,
                    Modifier.weight(1f),
                    role = TextRole.Small,
                    secondary = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (exchange.mockRuleId != null) Badge("mocked", BadgeTone.Info, Modifier.testTag(MOCKED + exchange.requestId))
                if (exchange.replayOf != null) Badge("replay", BadgeTone.Neutral, Modifier.testTag(REPLAYED + exchange.requestId))
                platform
                    ?.let(
                        ::platformLabel,
                    )?.takeIf { it.isNotEmpty() }
                    ?.let { Text(it, role = TextRole.Caption, secondary = true, maxLines = 1) }
                val duration = durationOf(exchange.startedAtMs, exchange.endedAtMs)
                Text(
                    duration?.let(::formatDuration) ?: "",
                    role = TextRole.Small,
                    warning = duration != null && duration > SLOW_MS,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
    }
}

internal fun toneOf(exchange: Exchange): BadgeTone =
    statusTone(exchange.failed, exchange.grpcStatus, exchange.isConnection, exchange.statusCode)

/** Green 2xx, blue 3xx, amber 4xx, red 5xx, a failure or a gRPC error; neutral while pending or for a socket. */
internal fun statusTone(
    failed: Boolean,
    grpcStatus: Int?,
    connection: Boolean,
    code: Int?,
): BadgeTone =
    when {
        failed || (grpcStatus ?: 0) != 0 -> BadgeTone.Error
        connection || code == null -> BadgeTone.Neutral
        code >= 500 -> BadgeTone.Error
        code >= 400 -> BadgeTone.Warning
        code >= 300 -> BadgeTone.Info
        code >= 200 -> BadgeTone.Success
        else -> BadgeTone.Neutral
    }

/** A start time to the millisecond, in this machine's zone, as the request rows show it. */
internal fun formatClock(ms: Long): String = CLOCK.format(Instant.ofEpochMilli(ms))

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.systemDefault())

// ADR-0151: the bounds a drag keeps each pane in, the widths at launch are PaneLayout's; the detail takes the rest.
private val DEVICES_MIN = 240.dp
private val DEVICES_MAX = 400.dp
private val REQUESTS_MIN = 360.dp
private val DETAIL_MIN = 360.dp

// The method's column, which the status badge under it shares, and the mark's box.
private val METHOD_WIDTH = 44.dp
private val MARK_SIZE = 20.dp

// A request slower than this shows its duration in the warning tone.
private const val SLOW_MS = 2_000L

// Rows from the end of the loaded ones at which the request list asks for the next page.
private const val LOAD_AHEAD = 50
