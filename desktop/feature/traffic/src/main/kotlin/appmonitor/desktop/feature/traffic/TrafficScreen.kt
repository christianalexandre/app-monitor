package appmonitor.desktop.feature.traffic

import androidx.compose.foundation.background
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
import appmonitor.desktop.designsystem.AppIcons
import appmonitor.desktop.designsystem.AppMonitorTheme
import appmonitor.desktop.designsystem.Badge
import appmonitor.desktop.designsystem.BadgeTone
import appmonitor.desktop.designsystem.Checkbox
import appmonitor.desktop.designsystem.Choice
import appmonitor.desktop.designsystem.Divider
import appmonitor.desktop.designsystem.EmptyState
import appmonitor.desktop.designsystem.IconButton
import appmonitor.desktop.designsystem.MenuAction
import appmonitor.desktop.designsystem.MenuButton
import appmonitor.desktop.designsystem.PaneDivider
import appmonitor.desktop.designsystem.PaneDividerWidth
import appmonitor.desktop.designsystem.PathText
import appmonitor.desktop.designsystem.Placeholder
import appmonitor.desktop.designsystem.SearchField
import appmonitor.desktop.designsystem.SegmentButton
import appmonitor.desktop.designsystem.SidebarItem
import appmonitor.desktop.designsystem.SidebarSection
import appmonitor.desktop.designsystem.Spacing
import appmonitor.desktop.designsystem.StatusBar
import appmonitor.desktop.designsystem.StatusDot
import appmonitor.desktop.designsystem.Text
import appmonitor.desktop.designsystem.TextButton
import appmonitor.desktop.designsystem.TextField
import appmonitor.desktop.designsystem.TextRole
import appmonitor.desktop.designsystem.TitleBar
import appmonitor.desktop.designsystem.selectableItem
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.domain.Session
import appmonitor.desktop.domain.appLabel
import appmonitor.desktop.domain.durationOf
import appmonitor.desktop.ui.METHODS
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
 * What the traffic panes show of other screens and open there: the server's [status], the MCP server's and proxy's
 * ([mcpStatus], [proxyStatus]), a [retentionWarning], the mocks', breakpoints' and export's labels (in the status bar too
 * while [mocksWaiting] or [callsHeld]), whether a dialog of theirs is [covered] over the panes, and the buttons that leave this
 * feature.
 */
data class TrafficLinks(
    val status: String = "",
    val mcpStatus: String? = null,
    val proxyStatus: String? = null,
    val retentionWarning: String? = null,
    val mocksLabel: String = "Mocks",
    val breakpointsLabel: String = "Breakpoints",
    val mocksWaiting: Boolean = false,
    val callsHeld: Boolean = false,
    val exportNote: String? = null,
    val covered: Boolean = false,
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

/** The title bar, the sidebar, the requests and the request, then the status bar (ADR-0205). */
@Composable
fun TrafficScreen(
    links: TrafficLinks,
    viewModel: TrafficViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // Kept while another request is picked, so the same text is looked for in each.
    var detailQuery by remember { mutableStateOf("") }
    val saved by viewModel.panes.collectAsState()
    val sidebarShown = saved.devicesShown
    var sidebarWidth by remember { mutableStateOf(saved.devicesWidth.dp.coerceIn(SIDEBAR_MIN, SIDEBAR_MAX)) }
    var requestsWidth by remember { mutableStateOf(saved.requestsWidth.dp.coerceAtLeast(REQUESTS_MIN)) }
    val keepWidths = {
        viewModel.setPanes(saved.copy(devicesWidth = sidebarWidth.value.roundToInt(), requestsWidth = requestsWidth.value.roundToInt()))
    }
    val toggleSidebar = { viewModel.setPanes(saved.copy(devicesShown = !sidebarShown)) }
    val clipboard = LocalClipboardManager.current
    val root = remember { FocusRequester() }
    val search = remember { FocusRequester() }
    var paletteShown by remember { mutableStateOf(false) }
    var paletteQuery by remember { mutableStateOf("") }
    val commands = TrafficCommands(state, links, viewModel) { viewModel.copy(DetailPart.Curl) { clipboard.setText(AnnotatedString(it)) } }
    val closePalette: () -> Unit = {
        paletteShown = false
        root.requestFocus()
    }
    val openPalette = {
        paletteQuery = ""
        paletteShown = true
    }
    // Key events reach a node only through the focused one: the window's root holds focus while no field does.
    // A dialog closing takes the focus with it: the root takes it back, or no shortcut works (ADR-0206).
    LaunchedEffect(links.covered) { if (!links.covered) root.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isShortcut) return@onPreviewKeyEvent false
                if (event.key == Key.K) return@onPreviewKeyEvent true.also { if (paletteShown) closePalette() else openPalette() }
                if (paletteShown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.F -> true.also { search.requestFocus() }
                    Key.B -> true.also { toggleSidebar() }
                    Key.R -> commands.record()
                    Key.S -> commands.save()
                    Key.E -> commands.exportHar()
                    Key.Comma -> commands.settings()
                    else -> false
                }
            }.focusRequester(root)
            .focusable(),
    ) {
        Column(Modifier.fillMaxSize()) {
            TrafficTitleBar(state, viewModel, sidebarShown, toggleSidebar, openPalette)
            val notes = listOfNotNull(state.runs.saveNote?.let { it to SAVE_NOTE }, links.exportNote?.let { it to EXPORT_NOTE })
            notes.forEach { (note, tag) ->
                Text(note, Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xxs).testTag(tag), role = TextRole.Caption)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val shownSidebar = if (sidebarShown) sidebarWidth + PaneDividerWidth else 0.dp
                val maxRequests = (maxWidth - shownSidebar - PaneDividerWidth - DETAIL_MIN).coerceAtLeast(REQUESTS_MIN)
                val requests = requestsWidth.coerceIn(REQUESTS_MIN, maxRequests)
                Row(Modifier.fillMaxSize()) {
                    if (sidebarShown) {
                        Sidebar(state, links, viewModel, Modifier.width(sidebarWidth).fillMaxHeight())
                        PaneDivider(
                            { sidebarWidth = (sidebarWidth + it).coerceIn(SIDEBAR_MIN, SIDEBAR_MAX) },
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
                                        onOpenReplayed = viewModel::openReplayed,
                                    )
                                } ?: Placeholder("Loading…")
                            }
                        }
                    }
                }
            }
            TrafficStatusBar(state, links)
        }
        if (paletteShown) TrafficPalette(commands, paletteQuery, { paletteQuery = it }, closePalette)
    }
}

/** The sidebar's toggle (⌘B), what is shown, Record or Stop and Clear, then "Actions ⌘K" for the palette (ADR-0206). */
@Composable
private fun TrafficTitleBar(
    state: TrafficState,
    viewModel: TrafficViewModel,
    sidebarShown: Boolean,
    onSidebar: () -> Unit,
    onPalette: () -> Unit,
) {
    val live = state.runs.shownRunId == null
    TitleBar {
        IconButton(
            AppIcons.Sidebar,
            (if (sidebarShown) "Hide the sidebar " else "Show the sidebar ") + SHORTCUT_PREFIX + "B",
            onClick = onSidebar,
            Modifier.testTag(DEVICES_TOGGLE),
        )
        Text(
            "App Monitor — ${state.runs.title}",
            Modifier.weight(1f),
            role = TextRole.SmallStrong,
            secondary = true,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (live) {
            if (state.runs.recording == null) {
                TextButton("Record", onClick = viewModel::record, Modifier.testTag(RECORD))
            } else {
                TextButton("Stop", onClick = viewModel::stop, Modifier.testTag(RECORD))
            }
            TextButton("Clear", onClick = viewModel::clear, Modifier.testTag(CLEAR))
        }
        TextButton("Actions ${SHORTCUT_PREFIX}K", onClick = onPalette, Modifier.testTag(ACTIONS))
    }
}

/** DEVICES, TRAFFIC and HOSTS, then a gear for the settings (ADR-0205). */
@Composable
private fun Sidebar(
    state: TrafficState,
    links: TrafficLinks,
    viewModel: TrafficViewModel,
    modifier: Modifier,
) {
    val colors = AppMonitorTheme.colors
    Column(modifier.background(colors.sidebar)) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = Spacing.sm)) {
            item { SidebarSection("Devices") }
            item {
                SidebarItem(
                    "All devices",
                    { viewModel.selectSession(null) },
                    Modifier.testTag(ALL_DEVICES),
                    selected = state.session == null,
                )
            }
            if (state.sessions.isEmpty() && state.disconnected.isEmpty()) {
                item { SidebarNote("A device shows here with its first request") }
            }
            items(state.sessions, key = { "session-${it.id}" }) { session -> DeviceItem(state, session, viewModel) }
            items(state.disconnected, key = { "disconnected-${it.deviceId}-${it.appId}" }) { device ->
                SidebarItem(
                    device.hello.device_name,
                    {},
                    sub = "${device.hello.appLabel} · refused",
                    dot = BadgeTone.Neutral,
                ) {
                    TextButton(
                        "Reconnect",
                        onClick = { viewModel.reconnect(device) },
                        Modifier.testTag(RECONNECT + device.deviceId + "-" + device.appId),
                    )
                }
            }
            item { SidebarSection("Traffic") }
            item { RunItems(state.runs, viewModel) }
            item {
                SidebarSection("Hosts") {
                    if (state.hosts.isNotEmpty()) {
                        val off = state.filter.noHost
                        IconButton(
                            if (off) AppIcons.Globe else AppIcons.Check,
                            if (off) "Show every host" else "Hide every host",
                            onClick = { viewModel.setFilter(state.filter.withEveryHost(shown = off)) },
                            Modifier.testTag(EVERY_HOST),
                            tint = colors.muted,
                        )
                    }
                }
            }
            if (state.hosts.isEmpty()) item { SidebarNote("No host in this list yet") }
            items(state.hosts, key = { "host-$it" }) { host ->
                val hidden = state.filter.hides(host)
                SidebarItem(
                    host,
                    { viewModel.setFilter(state.filter.withHost(host, shown = hidden, state.hosts)) },
                    Modifier.testTag(HOST_FILTER + host),
                    icon = if (hidden) AppIcons.Globe else AppIcons.Check,
                    iconTint = if (hidden) colors.muted else colors.primary,
                    dimmed = hidden,
                )
            }
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            IconButton(AppIcons.Gear, "Settings", onClick = links.onSettings, Modifier.testTag(SETTINGS_GEAR))
        }
    }
}

@Composable
private fun SidebarNote(text: String) {
    Text(text, Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs), role = TextRole.Caption, secondary = true)
}

/** A device: a connected dot, its app and OS, what it dropped, and Disconnect while connected. */
@Composable
private fun DeviceItem(
    state: TrafficState,
    session: Session,
    viewModel: TrafficViewModel,
) {
    val hello = session.hello
    val refused = state.refuses(session)
    val connected = !refused && session.disconnectedAtMs == null
    val condition =
        when {
            refused -> " · refused until Reconnect"
            connected -> ""
            else -> " · disconnected"
        }
    Column {
        SidebarItem(
            hello.device_name,
            { viewModel.selectSession(session.id) },
            selected = session.id == state.session?.id,
            sub = "${hello.appLabel} ${hello.app_version} · ${hello.os_version}$condition",
            dot = if (connected) BadgeTone.Success else BadgeTone.Neutral,
        )
        session.drops?.takeIf { it.any }?.let { drops ->
            Text(
                dropsLabel(drops),
                Modifier.padding(horizontal = Spacing.md).testTag(DROPS + session.id),
                error = true,
                role = TextRole.Caption,
            )
        }
        if (state.canDisconnect && state.runs.shownRunId == null && connected) {
            TextButton(
                "Disconnect",
                onClick = { viewModel.disconnect(session) },
                Modifier.padding(start = Spacing.lg).testTag(DISCONNECT + session.id),
            )
        }
    }
}

/** Live, every recording newest first (the running one with a red dot), then "Saved"; the shown one renames in place. */
@Composable
private fun RunItems(
    runs: RunsState,
    viewModel: TrafficViewModel,
) {
    val shownRunId = runs.shownRunId
    var editing by remember { mutableStateOf<Long?>(null) }
    var draft by remember { mutableStateOf("") }
    Column {
        SidebarItem(LIVE, { viewModel.showRun(null) }, Modifier.testTag(LIVE_RUN), selected = shownRunId == null, icon = AppIcons.Live)
        runs.recordings.forEach { run ->
            if (run.id == editing) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    TextField(draft, { draft = it }, Modifier.weight(1f).testTag(RUN_NAME), singleLine = true)
                    TextButton("Save", onClick = {
                        viewModel.rename(run.id, draft)
                        editing = null
                    }, Modifier.testTag(SAVE_RUN_NAME))
                }
            } else {
                val shown = run.id == shownRunId
                SidebarItem(
                    titleOf(run),
                    { viewModel.showRun(run.id) },
                    Modifier.testTag(RUN + run.id),
                    selected = shown,
                    sub = spanOf(run, recording = run.id == runs.recording),
                    icon = if (run.id == runs.recording) AppIcons.Record else AppIcons.RecordOutline,
                    iconTint = if (run.id == runs.recording) AppMonitorTheme.colors.error else null,
                ) {
                    if (shown) {
                        IconButton(AppIcons.Pencil, "Rename", onClick = {
                            draft = run.name.orEmpty()
                            editing = run.id
                        }, Modifier.testTag(RENAME_RUN))
                    }
                }
            }
        }
        if (runs.recordings.isEmpty()) SidebarNote("Record stores what the devices send until Stop")
        runs.saved?.let { saved ->
            SidebarItem(
                titleOf(saved),
                { viewModel.showRun(saved.id) },
                Modifier.testTag(SAVED_RUN),
                selected = saved.id == shownRunId,
                icon = AppIcons.Star,
            )
        }
    }
}

/** Listening, the devices connected, the recording, MCP, the proxy, what went wrong storing, what was dropped (ADR-0205). */
@Composable
private fun TrafficStatusBar(
    state: TrafficState,
    links: TrafficLinks,
) {
    val connected = state.sessions.count { it.disconnectedAtMs == null && !state.refuses(it) }
    val dropped = state.sessions.sumOf { (it.drops?.evicted ?: 0) + (it.drops?.oversized ?: 0) + (it.drops?.lost ?: 0) }
    StatusBar(Modifier.testTag(STATUS_BAR)) {
        StatusDot(if (links.status.startsWith("Listening")) BadgeTone.Success else BadgeTone.Error)
        StatusText(links.status)
        StatusText(if (connected == 1) "1 device connected" else "$connected devices connected")
        val recording = state.runs.recording
        if (recording == null) {
            StatusText("Not recording")
        } else {
            StatusDot(BadgeTone.Error)
            val since =
                state.runs.recordings
                    .firstOrNull { it.id == recording }
                    ?.let { " since ${formatMinute(it.startedAtMs)}" }
            StatusText("Recording${since.orEmpty()}")
        }
        links.mcpStatus?.let { StatusText(it) }
        links.proxyStatus?.let { StatusText(it) }
        state.health.lastError?.let { StatusText("${state.health.notSaved} events not saved: $it", error = true) }
        links.retentionWarning?.let { StatusText(it, error = true) }
        Spacer(Modifier.weight(1f))
        if (links.mocksWaiting) TextButton(links.mocksLabel, onClick = links.onMocks, singleLine = true)
        if (links.callsHeld) TextButton(links.breakpointsLabel, onClick = links.onBreakpoints, singleLine = true)
        StatusText("$dropped dropped", error = dropped > 0)
    }
}

@Composable
private fun StatusText(
    text: String,
    error: Boolean = false,
) {
    Text(text, role = TextRole.Caption, secondary = !error, error = error, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * The filter with [host] shown or hidden among [hosts]: the store keeps the hosts picked, none keeping all (ADR-0148), so
 * hiding one picks every other one, showing the last hidden one picks none again, and hiding the last one shown hides
 * every host (ADR-0209).
 */
internal fun ExchangeFilter.withHost(
    host: String,
    shown: Boolean,
    hosts: List<String>,
): ExchangeFilter {
    val picked = if (noHost) emptySet() else this.hosts.ifEmpty { hosts.toSet() }
    val next = if (shown) picked + host else picked - host
    return when {
        next.isEmpty() -> withEveryHost(shown = false)
        next.containsAll(hosts) -> withEveryHost(shown = true)
        else -> copy(hosts = next, noHost = false)
    }
}

/** The filter with every host shown, or every host hidden (ADR-0209). */
internal fun ExchangeFilter.withEveryHost(shown: Boolean): ExchangeFilter = copy(hosts = emptySet(), noHost = !shown)

/** Whether this filter hides [host] from the list. */
internal fun ExchangeFilter.hides(host: String): Boolean = noHost || (hosts.isNotEmpty() && host !in hosts)

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

/** The URL filter (⌘F) and the method, the status classes and how many requests are listed, over the list (ADR-0203, ADR-0205). */
@Composable
private fun ListHeader(
    state: TrafficState,
    viewModel: TrafficViewModel,
    search: FocusRequester,
) {
    val filter = state.filter
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SearchField(
                filter.text.orEmpty(),
                { viewModel.setFilter(filter.copy(text = it.ifEmpty { null })) },
                "Filter by URL",
                Modifier.weight(1f).focusRequester(search).testTag(SEARCH),
                shortcut = SHORTCUT_PREFIX + "F",
            )
            Box(
                Modifier.testTag(FILTERS),
            ) { Choice("Method", filter.method, METHODS, { it }) { viewModel.setFilter(filter.copy(method = it)) } }
        }
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

/** A recording's start, to the minute, in this machine's zone. */
internal fun formatMinute(ms: Long): String = MINUTE.format(Instant.ofEpochMilli(ms))

private val MINUTE = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault())

/** A start time to the millisecond, in this machine's zone, as the request rows show it. */
internal fun formatClock(ms: Long): String = CLOCK.format(Instant.ofEpochMilli(ms))

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.systemDefault())

// ADR-0151, ADR-0205: the bounds a drag keeps each pane in, the widths at launch are PaneLayout's; the detail the rest.
private val SIDEBAR_MIN = 220.dp
private val SIDEBAR_MAX = 400.dp
private val REQUESTS_MIN = 360.dp
private val DETAIL_MIN = 360.dp

// The method's column, which the status badge under it shares, and the mark's box.
private val METHOD_WIDTH = 44.dp
private val MARK_SIZE = 20.dp

// A request slower than this shows its duration in the warning tone.
private const val SLOW_MS = 2_000L

// Rows from the end of the loaded ones at which the request list asks for the next page.
private const val LOAD_AHEAD = 50
