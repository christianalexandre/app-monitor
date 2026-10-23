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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import appmonitor.desktop.app.store.CapturedBody
import appmonitor.desktop.app.store.CapturedFrame
import appmonitor.desktop.app.store.Device
import appmonitor.desktop.app.store.HttpExchange
import appmonitor.desktop.app.store.TrafficState
import appmonitor.desktop.server.DeviceKey
import appmonitor.desktop.server.PendingDevice
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Header
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Devices, then the selected device's requests, then the selected request; above them, the first device to approve. */
@Composable
internal fun AppMonitorApp(
    state: TrafficState,
    status: String,
    pending: List<PendingDevice> = emptyList(),
    onDecide: (DeviceKey, approved: Boolean) -> Unit = { _, _ -> },
) {
    var selectedDevice by remember { mutableStateOf<String?>(null) }
    var selectedRequest by remember { mutableStateOf<Pair<String, String>?>(null) }
    val device = state.devices.firstOrNull { it.sessionId == selectedDevice } ?: state.devices.firstOrNull()
    val exchanges = device?.let { state.exchangesOf(it.sessionId) }.orEmpty()
    val exchange = exchanges.firstOrNull { it.sessionId to it.requestId == selectedRequest }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                DeviceList(state.devices, device, status, Modifier.width(240.dp).fillMaxHeight()) {
                    selectedDevice = it.sessionId
                }
                Divider(Modifier.fillMaxHeight().width(1.dp))
                RequestList(exchanges, exchange, Modifier.weight(1f).fillMaxHeight()) {
                    selectedRequest = it.sessionId to it.requestId
                }
                Divider(Modifier.fillMaxHeight().width(1.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (exchange == null) {
                        Placeholder("Select a request")
                    } else {
                        // Body state (format, scroll) belongs to one exchange, even if another has the same bytes.
                        key(exchange.sessionId, exchange.requestId) { RequestDetail(exchange) }
                    }
                }
            }
            pending.firstOrNull()?.let { ApprovalDialog(it, waiting = pending.size - 1, onDecide) }
        }
    }
}

@Composable
private fun ApprovalDialog(
    device: PendingDevice,
    waiting: Int,
    onDecide: (DeviceKey, approved: Boolean) -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(SCRIM).clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
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
                    TextButton(onClick = { onDecide(device.key, false) }) { Text("Reject") }
                    TextButton(onClick = { onDecide(device.key, true) }) { Text("Approve") }
                }
            }
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<Device>,
    selected: Device?,
    status: String,
    modifier: Modifier,
    onSelect: (Device) -> Unit,
) {
    Column(modifier) {
        PaneTitle("Devices")
        Text(status, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.caption)
        LazyColumn {
            items(devices, key = { it.sessionId }) { device ->
                Column(Modifier.fillMaxWidth().selectable(device == selected) { onSelect(device) }.padding(12.dp)) {
                    Text(device.name, fontWeight = FontWeight.Medium)
                    Text("${device.appId} ${device.appVersion}", style = MaterialTheme.typography.caption)
                    Text(
                        "${device.osVersion} · ${if (device.connected) "connected" else "disconnected"}",
                        style = MaterialTheme.typography.caption,
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestList(
    exchanges: List<HttpExchange>,
    selected: HttpExchange?,
    modifier: Modifier,
    onSelect: (HttpExchange) -> Unit,
) {
    Column(modifier) {
        PaneTitle("Requests")
        if (exchanges.isEmpty()) Placeholder("No requests yet")
        LazyColumn {
            items(exchanges, key = { it.sessionId to it.requestId }) { exchange ->
                Row(
                    Modifier.fillMaxWidth().selectable(exchange == selected) { onSelect(exchange) }.padding(12.dp),
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
                    Text(exchange.durationMs?.let(::formatDuration) ?: "", Modifier.width(72.dp))
                }
            }
        }
    }
}

@Composable
private fun RequestDetail(exchange: HttpExchange) {
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
            if (exchange.frames.isNotEmpty()) {
                item { Section("WebSocket frames (${exchange.frames.size})") {} }
                exchange.frames.forEachIndexed { index, frame ->
                    item {
                        FrameRow(frame, exchange.startedAtMs, open = index == openFrame) {
                            openFrame = if (index == openFrame) null else index
                        }
                    }
                    if (index == openFrame && frameBody != null) body(frameBody)
                }
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

private fun Modifier.selectable(
    selected: Boolean,
    onClick: () -> Unit,
): Modifier = clickable(onClick = onClick).then(if (selected) Modifier.background(SELECTED) else Modifier)

private val SELECTED = Color(0x1F2196F3)

private val SCRIM = Color(0x66000000)
