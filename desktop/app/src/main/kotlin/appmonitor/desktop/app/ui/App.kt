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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
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
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StorageHealth
import appmonitor.desktop.storage.StoredApproval
import appmonitor.desktop.storage.StoredExchange
import appmonitor.desktop.storage.StoredSession
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Header
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Stored sessions, then the selected one's requests, then the selected request; above them, the first device to
 * approve, or the settings listing [answered] pairs. [onForget] gets null for every pair. Every pane reads [storage]
 * and follows its commits (SDD 09).
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
    val sessions by remember(storage) { storage.observeSessions() }.collectAsState(emptyList())
    val health by storage.health.collectAsState()
    var selectedSession by remember { mutableStateOf<Long?>(null) }
    var selectedRequest by remember { mutableStateOf<Long?>(null) }
    val session = sessions.firstOrNull { it.id == selectedSession } ?: sessions.firstOrNull()
    val pages = key(session?.id) { rememberExchangePages(storage, session?.id) }
    val row = pages.rows.firstOrNull { it.id == selectedRequest && it.sessionId == session?.id }
    var settingsOpen by remember { mutableStateOf(false) }

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
                ) {
                    selectedSession = it.id
                }
                Divider(Modifier.fillMaxHeight().width(1.dp))
                RequestList(pages, row, Modifier.weight(1f).fillMaxHeight()) {
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

/** The first rows of a session's requests; [loadMore] asks for one more page once the last one is full. */
private class ExchangePages(
    val rows: List<StoredExchange>,
    val loadMore: () -> Unit,
)

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun rememberExchangePages(
    storage: Storage,
    sessionId: Long?,
): ExchangePages {
    val limit = remember(sessionId) { MutableStateFlow(Storage.PAGE_SIZE) }
    val rows by remember(storage, sessionId) {
        if (sessionId == null) flowOf(emptyList()) else limit.flatMapLatest { storage.observeExchangesOfSession(sessionId, it) }
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
    onSelect: (StoredSession) -> Unit,
) {
    Column(modifier) {
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
        if (exchanges.isEmpty()) Placeholder("No requests yet")
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

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
