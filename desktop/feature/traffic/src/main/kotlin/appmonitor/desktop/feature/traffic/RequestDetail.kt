package appmonitor.desktop.feature.traffic

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import appmonitor.desktop.designsystem.Placeholder
import appmonitor.desktop.designsystem.Section
import appmonitor.desktop.designsystem.Spacing
import appmonitor.desktop.designsystem.Text
import appmonitor.desktop.designsystem.TextButton
import appmonitor.desktop.designsystem.TextRole
import appmonitor.desktop.designsystem.selectableItem
import appmonitor.desktop.domain.CapturedBody
import appmonitor.desktop.domain.CapturedFrame
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.ui.formatDuration
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Header
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun RequestDetail(
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

// ADR-0110 E.1: the device names the rule; a deleted one is left with its id.
private fun mockedBy(exchange: HttpExchange): String =
    exchange.mockRuleName?.let { "Mocked by rule \"$it\"" } ?: "Mocked by deleted rule ${exchange.mockRuleId}"

private fun countOf(bytes: Long?): String = bytes?.let { "$it B" } ?: "—"
