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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import appmonitor.desktop.app.store.Device
import appmonitor.desktop.app.store.HttpExchange
import appmonitor.desktop.app.store.TrafficState
import appmonitor.desktop.server.DeviceKey
import appmonitor.desktop.server.PendingDevice
import appmonitor.v1.Header

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
    SelectionContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text("${exchange.method ?: "—"} ${exchange.url ?: exchange.requestId}", fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(
                    "Status ${statusOf(exchange)}",
                    exchange.durationMs?.let { "Duration ${formatDuration(it)}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.caption,
            )
            exchange.error?.let { Text(it, color = MaterialTheme.colors.error) }
            Section("Request headers") { Headers(exchange.requestHeaders) }
            Section("Request body") { Body(exchange.requestHeaders, exchange.requestBody) }
            Section("Response headers") { Headers(exchange.responseHeaders) }
            Section("Response body") { Body(exchange.responseHeaders, exchange.responseBody) }
        }
    }
}

@Composable
private fun Headers(headers: List<Header>) {
    if (headers.isEmpty()) Text("None", style = MaterialTheme.typography.caption)
    headers.forEach { Text("${it.name}: ${it.value_}", fontFamily = FontFamily.Monospace) }
}

@Composable
private fun Body(
    headers: List<Header>,
    captured: CapturedBody,
) {
    val body = captured.bytes
    if (captured.truncated) Text(truncationNote(captured), style = MaterialTheme.typography.caption)
    if (body.size == 0) {
        if (!captured.truncated) Text("None", style = MaterialTheme.typography.caption)
        return
    }
    val decoded = remember(body) { DecodedBody(body) }
    var format by remember(decoded) { mutableStateOf(defaultFormat(headers, decoded)) }
    Row {
        BodyFormat.entries.forEach { option ->
            TextButton(onClick = { format = option }, enabled = option != format) { Text(option.label) }
        }
    }
    val rendered = remember(decoded, format) { render(decoded, format) }
    Text(rendered, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, softWrap = false)
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
