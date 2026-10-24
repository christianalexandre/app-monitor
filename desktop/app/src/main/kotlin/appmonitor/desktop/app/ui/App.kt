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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import appmonitor.desktop.app.store.CapturedBody
import appmonitor.desktop.app.store.CapturedFrame
import appmonitor.desktop.app.store.HttpExchange
import appmonitor.desktop.server.DeviceKey
import appmonitor.desktop.server.PendingDevice
import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.StatusClass
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StorageHealth
import appmonitor.desktop.storage.StoredApproval
import appmonitor.desktop.storage.StoredExchange
import appmonitor.desktop.storage.StoredRun
import appmonitor.desktop.storage.StoredSession
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The shown run's sessions, then the selected one's requests, then the selected request; above them, the first device
 * to approve, the runs, or the settings listing [answered] pairs. [onForget] gets null for every pair. Every pane reads
 * [storage] and follows its commits (SDD 09).
 */
@Composable
internal fun AppMonitorApp(
    storage: Storage,
    status: String,
    pending: List<PendingDevice> = emptyList(),
    onDecide: (PendingDevice, approved: Boolean) -> Unit = { _, _ -> },
    answered: List<StoredApproval> = emptyList(),
    onForget: (DeviceKey?) -> Unit = {},
) {
    val currentRun by storage.currentRun.collectAsState()
    val runs by remember(storage) { storage.observeRuns() }.collectAsState(emptyList())
    var shown by remember { mutableStateOf<ShownRun>(ShownRun.Current) }
    val shownRunId =
        when (val choice = shown) {
            ShownRun.Current -> currentRun
            ShownRun.All -> null
            is ShownRun.Earlier -> choice.id
        }
    val sessions by remember(storage, shownRunId) { storage.observeSessions(shownRunId) }.collectAsState(emptyList())
    val health by storage.health.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedSession by remember { mutableStateOf<Long?>(null) }
    var selectedRequest by remember { mutableStateOf<Long?>(null) }
    val session = sessions.firstOrNull { it.id == selectedSession } ?: sessions.firstOrNull()
    var filter by remember { mutableStateOf(ExchangeFilter()) }
    val pages = key(session?.id, filter) { rememberExchangePages(storage, session?.id, filter) }
    val row = pages.rows.firstOrNull { it.id == selectedRequest && it.sessionId == session?.id }
    var settingsOpen by remember { mutableStateOf(false) }
    var runsOpen by remember { mutableStateOf(false) }
    val runTitle = if (shownRunId == null) "All runs" else runs.firstOrNull { it.id == shownRunId }?.let(::titleOf) ?: ""

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                DeviceList(
                    sessions,
                    session,
                    status,
                    health,
                    Modifier.width(240.dp).fillMaxHeight(),
                    onSettings = { settingsOpen = true },
                    runTitle = runTitle,
                    onRuns = { runsOpen = true },
                ) {
                    selectedSession = it.id
                }
                Divider(Modifier.fillMaxHeight().width(1.dp))
                RequestList(pages, row, filter, { filter = it }, Modifier.weight(1f).fillMaxHeight()) {
                    selectedRequest = it.id
                }
                Divider(Modifier.fillMaxHeight().width(1.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (row == null) {
                        Placeholder("Select a request")
                    } else {
                        // Body state (format, scroll) belongs to one exchange, even if another has the same bytes.
                        key(row.id) { StoredRequestDetail(storage, row) }
                    }
                }
            }
            if (settingsOpen) DeviceSettings(answered, onForget) { settingsOpen = false }
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
                        scope.launch { storage.newRun() }
                        shown = ShownRun.Current
                        runsOpen = false
                    },
                    onRename = { id, name -> scope.launch { storage.rename(id, name) } },
                ) { runsOpen = false }
            }
            pending.firstOrNull()?.let { ApprovalDialog(it, waiting = pending.size - 1, onDecide) }
        }
    }
}

/** What the window shows when the store did not open: no server runs behind it (SDD 09). */
@Composable
internal fun StorageUnavailable(
    file: String,
    error: String,
) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("App Monitor could not open its data", style = MaterialTheme.typography.h6)
                Text(error, color = MaterialTheme.colors.error)
                Text(file, fontFamily = FontFamily.Monospace)
                Text("No device is admitted until the file opens.", style = MaterialTheme.typography.caption)
            }
        }
    }
}

/** The first rows of a session's requests [filter] matches; [loadMore] asks for one more page once the last one is full. */
private class ExchangePages(
    val rows: List<StoredExchange>,
    val loadMore: () -> Unit,
)

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun rememberExchangePages(
    storage: Storage,
    sessionId: Long?,
    filter: ExchangeFilter,
): ExchangePages {
    val limit = remember(sessionId, filter) { MutableStateFlow(Storage.PAGE_SIZE) }
    val rows by remember(storage, sessionId, filter) {
        if (sessionId == null) {
            flowOf(emptyList())
        } else {
            limit.flatMapLatest { storage.observeExchanges(filter.copy(sessionId = sessionId), it) }
        }
    }.collectAsState(emptyList())
    return ExchangePages(rows) { if (rows.size >= limit.value) limit.value += Storage.PAGE_SIZE }
}

/** Reads one exchange, its bodies and its first frames again whenever its [row] changes. */
@Composable
private fun StoredRequestDetail(
    storage: Storage,
    row: StoredExchange,
) {
    var frameLimit by remember { mutableStateOf(Storage.PAGE_SIZE) }
    val exchange by produceState<HttpExchange?>(null, row, frameLimit) {
        value = storage.exchange(row.id)?.let { HttpExchange(it, storage.frames(row.id, limit = frameLimit)) }
    }
    exchange?.let { RequestDetail(it) { frameLimit += Storage.PAGE_SIZE } } ?: Placeholder("Loading…")
}

@Composable
private fun ApprovalDialog(
    device: PendingDevice,
    waiting: Int,
    onDecide: (PendingDevice, approved: Boolean) -> Unit,
) {
    Overlay {
        Card(Modifier.width(420.dp), elevation = 8.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${device.hello.device_name.ifEmpty { "Unnamed device" }} — ${device.key.appId} wants to connect",
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    listOf(device.hello.os_version, device.hello.app_version, "device ${device.key.deviceId}")
                        .filter { it.isNotEmpty() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.caption,
                )
                if (waiting > 0) Text("$waiting more waiting", style = MaterialTheme.typography.caption)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onDecide(device, false) }) { Text("Reject") }
                    TextButton(onClick = { onDecide(device, true) }) { Text("Approve") }
                }
            }
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
    runs: List<StoredRun>,
    currentRun: Long,
    shownRunId: Long?,
    onShow: (Long?) -> Unit,
    onNewRun: () -> Unit,
    onRename: (Long, String) -> Unit,
    onClose: () -> Unit,
) {
    var editing by remember { mutableStateOf<Long?>(null) }
    var draft by remember { mutableStateOf("") }
    Overlay {
        Card(Modifier.width(520.dp), elevation = 8.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Runs", style = MaterialTheme.typography.h6)
                Text(
                    "All runs",
                    Modifier.fillMaxWidth().selectable(shownRunId == null) { onShow(null) }.padding(vertical = 8.dp),
                    fontWeight = FontWeight.Medium,
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(runs, key = { it.id }) { run ->
                        Row(
                            Modifier.fillMaxWidth().selectable(run.id == shownRunId) { onShow(run.id) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (run.id == editing) {
                                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f).testTag(RUN_NAME), singleLine = true)
                                TextButton(onClick = {
                                    onRename(run.id, draft)
                                    editing = null
                                }) { Text("Save") }
                            } else {
                                Column(Modifier.weight(1f)) {
                                    Text(titleOf(run), fontWeight = FontWeight.Medium)
                                    Text(spanOf(run, current = run.id == currentRun), style = MaterialTheme.typography.caption)
                                }
                                TextButton(onClick = {
                                    draft = run.name.orEmpty()
                                    editing = run.id
                                }) { Text("Rename") }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onNewRun) { Text("New run") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

/** The settings' "Devices" section: every answered pair, each with "Forget", and "Forget all" (ADR-0083 C.1). */
@Composable
private fun DeviceSettings(
    answered: List<StoredApproval>,
    onForget: (DeviceKey?) -> Unit,
    onClose: () -> Unit,
) {
    Overlay {
        Card(Modifier.width(520.dp), elevation = 8.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Settings", style = MaterialTheme.typography.h6)
                Text("Devices", style = MaterialTheme.typography.subtitle2)
                if (answered.isEmpty()) Text("No device answered yet", style = MaterialTheme.typography.caption)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(answered, key = { "${it.deviceId}/${it.appId}" }) { pair ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${pair.deviceName.ifEmpty { "Unnamed device" }} — ${pair.appId}", fontWeight = FontWeight.Medium)
                                Text(
                                    listOf(
                                        if (pair.approved) "Approved" else "Rejected",
                                        DATE_FORMAT.format(Instant.ofEpochMilli(pair.decidedAtMs)),
                                        pair.osVersion,
                                        pair.appVersion,
                                    ).filter { it.isNotEmpty() }.joinToString(" · "),
                                    style = MaterialTheme.typography.caption,
                                )
                            }
                            TextButton(onClick = { onForget(DeviceKey(pair.deviceId, pair.appId)) }) { Text("Forget") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onForget(null) }, enabled = answered.isNotEmpty()) { Text("Forget all") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

/** Dims the window and takes its clicks; [content] is centred. */
@Composable
private fun Overlay(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(SCRIM).clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun DeviceList(
    sessions: List<StoredSession>,
    selected: StoredSession?,
    status: String,
    health: StorageHealth,
    modifier: Modifier,
    onSettings: () -> Unit,
    runTitle: String,
    onRuns: () -> Unit,
    onSelect: (StoredSession) -> Unit,
) {
    Column(modifier) {
        TextButton(onClick = onRuns, Modifier.padding(horizontal = 4.dp).testTag(RUNS)) {
            Text("$runTitle ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PaneTitle("Devices") }
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        Text(status, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.caption)
        health.lastError?.let {
            Text(
                "${health.notSaved} events not saved: $it",
                Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colors.error,
                style = MaterialTheme.typography.caption,
            )
        }
        LazyColumn {
            items(sessions, key = { it.id }) { session ->
                val hello = session.hello
                Column(Modifier.fillMaxWidth().selectable(session.id == selected?.id) { onSelect(session) }.padding(12.dp)) {
                    Text(hello.device_name, fontWeight = FontWeight.Medium)
                    Text("${hello.app_id} ${hello.app_version}", style = MaterialTheme.typography.caption)
                    Text(
                        "${hello.os_version} · ${if (session.disconnectedAtMs == null) "connected" else "disconnected"}",
                        style = MaterialTheme.typography.caption,
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestList(
    pages: ExchangePages,
    selected: StoredExchange?,
    filter: ExchangeFilter,
    onFilter: (ExchangeFilter) -> Unit,
    modifier: Modifier,
    onSelect: (StoredExchange) -> Unit,
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
        PaneTitle("Requests")
        FilterBar(filter, onFilter)
        if (exchanges.isEmpty()) Placeholder(if (filter == ExchangeFilter()) "No requests yet" else "No requests match")
        LazyColumn(Modifier.testTag(REQUEST_LIST), state = list) {
            items(exchanges, key = { it.id }) { exchange ->
                Row(
                    Modifier.fillMaxWidth().selectable(exchange.id == selected?.id) { onSelect(exchange) }.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(exchange.method ?: "—", Modifier.width(64.dp), fontWeight = FontWeight.Medium)
                    Text(
                        exchange.url ?: exchange.requestId,
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
    Column(Modifier.padding(horizontal = 12.dp)) {
        OutlinedTextField(
            filter.text.orEmpty(),
            { onFilter(filter.copy(text = it.ifEmpty { null })) },
            Modifier.fillMaxWidth().testTag(SEARCH),
            placeholder = { Text("Search URL") },
            singleLine = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Choice("Method", filter.method, METHODS, { it }) { onFilter(filter.copy(method = it)) }
            Choice("Status", filter.statusClass, StatusClass.entries, ::labelOf) { onFilter(filter.copy(statusClass = it)) }
            Row(
                Modifier.toggleable(filter.failedOnly) { onFilter(filter.copy(failedOnly = it)) }.padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(filter.failedOnly, onCheckedChange = null)
                Text("Failed only")
            }
        }
    }
}

/** A button showing [label] and the [chosen] option, opening a menu of "Any" and [options]. */
@Composable
private fun <T : Any> Choice(
    label: String,
    chosen: T?,
    options: List<T>,
    nameOf: (T) -> String,
    onChoose: (T?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("$label: ${chosen?.let(nameOf) ?: "Any"} ▾") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            (listOf(null) + options).forEach { option ->
                DropdownMenuItem(onClick = {
                    onChoose(option)
                    open = false
                }) { Text(option?.let(nameOf) ?: "Any") }
            }
        }
    }
}

private val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

private fun labelOf(status: StatusClass): String = "${status.from / 100}xx"

@Composable
private fun RequestDetail(
    exchange: HttpExchange,
    onMoreFrames: () -> Unit,
) {
    val requestBody = rememberBodyView(exchange.requestHeaders, exchange.requestBody)
    val responseBody = rememberBodyView(exchange.responseHeaders, exchange.responseBody)
    var openFrame by remember { mutableStateOf<Int?>(null) }
    val frameBody = openFrame?.let { exchange.frames.getOrNull(it) }?.let { key(openFrame) { rememberBodyView(emptyList(), it.payload) } }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
            item {
                Text("${exchange.method ?: "—"} ${exchange.url ?: exchange.requestId}", fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(
                        "Status ${statusOf(exchange)}",
                        exchange.durationMs?.let { "Duration ${formatDuration(it)}" },
                        layerOf(exchange.captureLayer),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.caption,
                )
                exchange.error?.let { Text(it, color = MaterialTheme.colors.error) }
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
                if (exchange.frames.size < exchange.frameCount) item { TextButton(onClick = onMoreFrames) { Text("Show more frames") } }
            }
        }
    }
}

@Composable
private fun Headers(headers: List<Header>) {
    if (headers.isEmpty()) Text("None", style = MaterialTheme.typography.caption)
    headers.forEach { Text("${it.name}: ${it.value_}", fontFamily = FontFamily.Monospace) }
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
        if (captured.truncated) Text(truncationNote(captured), style = MaterialTheme.typography.caption)
        if (captured.bytes.size == 0 && !captured.truncated) Text("None", style = MaterialTheme.typography.caption)
        if (captured.bytes.size > 0) {
            Row {
                BodyFormat.entries.forEach { option ->
                    TextButton(onClick = { view.choose(option) }, enabled = option != view.rendered?.format) { Text(option.label) }
                }
            }
        }
    }
    if (captured.bytes.size == 0) return
    val chunks = view.rendered?.chunks
    if (chunks == null) {
        item { Placeholder("Rendering…") }
    } else {
        items(chunks) { Text(it, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, softWrap = false) }
    }
}

@Composable
private fun FrameRow(
    frame: CapturedFrame,
    startedAtMs: Long?,
    open: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().selectable(open, onClick).padding(vertical = 4.dp)) {
        Text(
            listOfNotNull(
                directionOf(frame.direction),
                frame.opcode.name.removePrefix("OPCODE_"),
                sizeOf(frame.payload),
                startedAtMs?.let { "+${formatDuration(frame.atMs - it)}" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.caption,
        )
        framePreview(frame)?.let { Text(it, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.subtitle2)
    content()
}

@Composable
private fun PaneTitle(title: String) {
    Text(title, Modifier.padding(12.dp), style = MaterialTheme.typography.h6)
}

@Composable
private fun Placeholder(text: String) {
    Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.caption)
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
        exchange.statusCode != null -> exchange.statusCode.toString()
        else -> "…"
    }

private fun statusOf(exchange: StoredExchange): String =
    when {
        exchange.failed -> "failed"
        exchange.statusCode != null -> exchange.statusCode.toString()
        else -> "…"
    }

private fun durationOf(
    startedAtMs: Long?,
    endedAtMs: Long?,
): Long? = if (startedAtMs != null && endedAtMs != null) endedAtMs - startedAtMs else null

private fun Modifier.selectable(
    selected: Boolean,
    onClick: () -> Unit,
): Modifier = clickable(onClick = onClick).then(if (selected) Modifier.background(SELECTED) else Modifier)

private val SELECTED = Color(0x1F2196F3)

private val SCRIM = Color(0x66000000)

/** A run's name, or its start time while it has none (ADR-0084 B.1). */
private fun titleOf(run: StoredRun): String = run.name ?: RUN_FORMAT.format(Instant.ofEpochMilli(run.startedAtMs))

private fun spanOf(
    run: StoredRun,
    current: Boolean,
): String =
    listOfNotNull(
        RUN_FORMAT.format(Instant.ofEpochMilli(run.startedAtMs)) +
            (run.endedAtMs?.let { " – ${TIME_FORMAT.format(Instant.ofEpochMilli(it))}" } ?: ""),
        if (current) "current" else null,
    ).joinToString(" · ")

private val RUN_FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault())

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
