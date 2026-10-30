package appmonitor.desktop.feature.traffic

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import appmonitor.desktop.designsystem.Badge
import appmonitor.desktop.designsystem.BadgeTone
import appmonitor.desktop.designsystem.BlockEdge
import appmonitor.desktop.designsystem.CodeBlock
import appmonitor.desktop.designsystem.CodeText
import appmonitor.desktop.designsystem.Divider
import appmonitor.desktop.designsystem.GlyphButton
import appmonitor.desktop.designsystem.Placeholder
import appmonitor.desktop.designsystem.SearchField
import appmonitor.desktop.designsystem.Section
import appmonitor.desktop.designsystem.Spacing
import appmonitor.desktop.designsystem.Tabs
import appmonitor.desktop.designsystem.Text
import appmonitor.desktop.designsystem.TextButton
import appmonitor.desktop.designsystem.TextField
import appmonitor.desktop.designsystem.TextRole
import appmonitor.desktop.designsystem.Toast
import appmonitor.desktop.designsystem.ToggleChip
import appmonitor.desktop.designsystem.lineHeightOf
import appmonitor.desktop.designsystem.selectableItem
import appmonitor.desktop.domain.CapturedBody
import appmonitor.desktop.domain.CapturedFrame
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.ui.formatDuration
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Header
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RequestDetail(
    exchange: HttpExchange,
    detail: DetailState,
    onTab: (DetailTab) -> Unit,
    onCopy: (DetailPart) -> Unit,
    onMock: () -> Unit,
    onReplay: (() -> Unit)?,
    onShowOriginal: (Long) -> Unit,
    onMoreFrames: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
) {
    val requestBody = rememberBodyView(exchange.requestHeaders, exchange.requestBody)
    val responseBody = rememberBodyView(exchange.responseHeaders, exchange.responseBody)
    // By id: the live list cuts the oldest frames, so an index would move to another one.
    var openFrame by remember { mutableStateOf<Long?>(null) }
    val frameBody =
        openFrame
            ?.let { id ->
                exchange.frames.firstOrNull { it.id == id }
            }?.let { key(openFrame) { rememberBodyView(emptyList(), it.payload) } }
    val tabs = tabsOf(exchange)
    val tab = detail.tab.takeIf { it in tabs } ?: DetailTab.Summary
    val requestRendered = requestBody.rendered
    val responseRendered = responseBody.rendered
    // ADR-0029: a body can hold 16 MiB of text, too much to scan on the UI thread at each key.
    val found by produceState(Found(), exchange, requestRendered, responseRendered, query) {
        value =
            withContext(Dispatchers.Default) {
                val request = requestRendered?.chunks.orEmpty()
                val response = responseRendered?.chunks.orEmpty()
                Found(matchCounts(searchedTexts(exchange, request + response), query), bodyMatches(request, response, query))
            }
    }
    val counts = found.counts
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var current by remember(query, found) { mutableStateOf(0) }
    val lineHeight = with(LocalDensity.current) { lineHeightOf(TextRole.Code).roundToPx() }
    val requestRows = if (exchange.requestBody.bytes.size == 0) 0 else requestRendered?.chunks?.size ?: 1
    val jumpTo: (Int) -> Unit = { index ->
        current = index
        found.body.getOrNull(index)?.let { match ->
            scope.launch { list.scrollToItem(bodyRowOf(match, requestRows), ((match.line - CONTEXT_LINES) * lineHeight).coerceAtLeast(0)) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Badge(
                    statusOf(exchange),
                    statusTone(exchange.error != null, exchange.grpcStatus, exchange.connection != null, exchange.statusCode),
                    Modifier.padding(end = Spacing.sm),
                )
                SelectionContainer(Modifier.weight(1f)) {
                    Text("${exchange.method ?: "—"} ${exchange.url ?: exchange.requestId}", role = TextRole.BodyStrong)
                }
                if (exchange.connection == null && exchange.url?.startsWith("ws") != true) {
                    TextButton("Mock this", onClick = onMock, Modifier.testTag(MOCK_THIS))
                    if (onReplay != null && exchange.captureLayer != CaptureLayer.CAPTURE_LAYER_PROXY) {
                        TextButton(replayLabelOf(exchange.method), onClick = onReplay, Modifier.testTag(SEND_AGAIN))
                    }
                }
            }
            Tabs(
                tabs.map { t -> counts[t]?.takeIf { it > 0 }?.let { "${t.label} · $it" } ?: t.label },
                tabs.indexOf(tab),
                { onTab(tabs[it]) },
                Modifier.padding(horizontal = Spacing.lg),
            )
            Row(
                Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(query, onQuery, "Search in this request", Modifier.weight(1f).testTag(DETAIL_SEARCH))
                if (query.isNotBlank()) {
                    val jumps = tab == DetailTab.Body && found.body.isNotEmpty()
                    Text(
                        if (jumps) "${current + 1} of ${found.body.size}" else matchSummary(counts.values.sum()),
                        Modifier.testTag(DETAIL_MATCHES),
                        role = TextRole.Caption,
                    )
                    if (jumps) {
                        val size = found.body.size
                        GlyphButton(
                            "↑",
                            "Previous match",
                            onClick = { jumpTo((current - 1 + size) % size) },
                            Modifier.testTag(PREVIOUS_MATCH),
                        )
                        GlyphButton("↓", "Next match", onClick = { jumpTo((current + 1) % size) }, Modifier.testTag(NEXT_MATCH))
                    }
                }
            }
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = Spacing.lg).padding(bottom = Spacing.lg), state = list) {
                    when (tab) {
                        DetailTab.Summary -> {
                            summary(exchange, detail.replayNote, query, onCopy, onShowOriginal)
                        }

                        DetailTab.Headers -> {
                            item {
                                CopyHeading(countedTitle("Request headers", exchange.requestHeaders), DetailPart.RequestHeaders, onCopy)
                                Headers(exchange.requestHeaders, query)
                                CopyHeading(countedTitle("Response headers", exchange.responseHeaders), DetailPart.ResponseHeaders, onCopy)
                                Headers(exchange.responseHeaders, query)
                            }
                        }

                        DetailTab.Body -> {
                            item { CopyHeading("Request body", DetailPart.RequestBody, onCopy) }
                            val shown = found.body.getOrNull(current)
                            body(requestBody, query, shown?.takeIf { !it.response })
                            item { CopyHeading("Response body", DetailPart.ResponseBody, onCopy) }
                            body(responseBody, query, shown?.takeIf { it.response })
                            frames(exchange, openFrame, frameBody, query, { openFrame = if (it == openFrame) null else it }, onMoreFrames)
                        }

                        DetailTab.Timing -> {
                            item {
                                CopyHeading("Timing", DetailPart.Timing, onCopy)
                                timingOf(exchange).forEach { (name, value) ->
                                    Row {
                                        Text(name, Modifier.width(96.dp), role = TextRole.Caption)
                                        CodeText(value, query = query)
                                    }
                                }
                            }
                        }

                        DetailTab.Curl -> {
                            item {
                                CopyHeading("cURL", DetailPart.Curl, onCopy)
                                val command = detail.curl
                                when {
                                    command == null -> Placeholder("Loading…")
                                    command.isSuccess -> CodeBlock { CodeText(command.getOrThrow(), Modifier.testTag(CURL), query = query) }
                                    else -> Text(command.exceptionOrNull()?.message.orEmpty(), role = TextRole.Caption)
                                }
                            }
                        }
                    }
                }
            }
        }
        detail.copyNote?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(Spacing.lg).testTag(COPY_NOTE)) }
    }
}

private fun LazyListScope.summary(
    exchange: HttpExchange,
    replayNote: String?,
    query: String,
    onCopy: (DetailPart) -> Unit,
    onShowOriginal: (Long) -> Unit,
) {
    item {
        Spacer(Modifier.height(Spacing.md))
        Text(
            listOfNotNull(
                "Status ${statusOf(exchange)}",
                exchange.durationMs?.let { "Duration ${formatDuration(it)}" },
                layerOf(exchange.captureLayer),
                exchange.mockRuleId?.let { mockedBy(exchange) },
            ).joinToString(" · "),
            role = TextRole.Caption,
        )
        exchange.error?.let { CodeText(it, query = query, role = TextRole.Body, error = true) }
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
        exchange.url?.let {
            CopyHeading("URL", DetailPart.Url, onCopy)
            CodeBlock { CodeText(it, query = query) }
        }
        exchange.connection?.let { bytes ->
            Section("Connection") {
                Text("Sent ${countOf(bytes.sent)} · Received ${countOf(bytes.received)}", Modifier.testTag(CONNECTION_BYTES))
                Text("Metadata only: no headers or body are captured at this layer", role = TextRole.Caption)
            }
        }
    }
}

private fun LazyListScope.frames(
    exchange: HttpExchange,
    openFrame: Long?,
    frameBody: BodyView?,
    query: String,
    onFrame: (Long) -> Unit,
    onMoreFrames: () -> Unit,
) {
    if (exchange.frameCount == 0L) return
    val count =
        if (exchange.framesKept <
            exchange.frameCount
        ) {
            "last ${exchange.framesKept} of ${exchange.frameCount}"
        } else {
            "${exchange.frameCount}"
        }
    item { Section("WebSocket frames ($count)") {} }
    exchange.frames.forEach { frame ->
        item { FrameRow(frame, exchange.startedAtMs, open = frame.id == openFrame) { onFrame(frame.id) } }
        if (frame.id == openFrame && frameBody != null) body(frameBody, query, null)
    }
    if (exchange.frames.size < exchange.framesKept) item { TextButton("Show more frames", onClick = onMoreFrames) }
}

/** A tab's heading with the copy action of its [part]. */
@Composable
private fun CopyHeading(
    title: String,
    part: DetailPart,
    onCopy: (DetailPart) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), role = TextRole.Heading)
        TextButton("Copy", onClick = { onCopy(part) }, Modifier.testTag(COPY + part.name))
    }
}

/** One header a row: its name small and muted above its value, a rule between rows, in the order sent. */
@Composable
private fun Headers(
    headers: List<Header>,
    query: String,
) {
    if (headers.isEmpty()) Text("None", role = TextRole.Caption)
    headers.forEachIndexed { index, header ->
        if (index > 0) Divider()
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
            CodeText(header.name, query = query, role = TextRole.CodeCaption, muted = true)
            CodeText(header.value_, query = query)
        }
    }
}

private fun countedTitle(
    title: String,
    headers: List<Header>,
): String = if (headers.isEmpty()) title else "$title (${headers.size})"

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

private fun LazyListScope.body(
    view: BodyView,
    query: String,
    shown: BodyMatch?,
) {
    val captured = view.captured
    item {
        if (captured.truncated) Text(truncationNote(captured), role = TextRole.Caption)
        if (captured.bytes.size == 0 && !captured.truncated) Text("None", role = TextRole.Caption)
        if (captured.bytes.size > 0) {
            Row(Modifier.padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                BodyFormat.entries.forEach { option ->
                    ToggleChip(option.label, BadgeTone.Info, selected = option == view.rendered?.format, onToggle = { view.choose(option) })
                }
            }
        }
    }
    if (captured.bytes.size == 0) return
    val rendered = view.rendered
    if (rendered == null) {
        item { Placeholder("Rendering…") }
    } else {
        val json = rendered.format == BodyFormat.Json
        itemsIndexed(rendered.chunks) { index, chunk ->
            CodeBlock(edgeOf(index, rendered.chunks.size)) {
                CodeText(
                    chunk,
                    Modifier.horizontalScroll(rememberScrollState()),
                    query = query,
                    syntax = if (json) remember(chunk) { jsonSyntax(chunk) } else emptyList(),
                    softWrap = false,
                    current = shown?.takeIf { it.chunk == index }?.inChunk,
                )
            }
        }
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

/** What the search found: counts per tab, and the body's matches in reading order to jump between. */
private data class Found(
    val counts: Map<DetailTab, Int> = emptyMap(),
    val body: List<BodyMatch> = emptyList(),
)

// The Body tab's rows: per side a heading and a notes row, then its chunks (one "Rendering…" row while it renders).
private fun bodyRowOf(
    match: BodyMatch,
    requestRows: Int,
): Int = if (match.response) 2 + requestRows + 2 + match.chunk else 2 + match.chunk

// Lines shown above a match jumped to.
private const val CONTEXT_LINES = 3

internal fun matchSummary(total: Int): String =
    when (total) {
        0 -> "No match"
        1 -> "1 match"
        else -> "$total matches"
    }

private fun edgeOf(
    index: Int,
    count: Int,
): BlockEdge =
    when {
        count == 1 -> BlockEdge.Only
        index == 0 -> BlockEdge.First
        index == count - 1 -> BlockEdge.Last
        else -> BlockEdge.Middle
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
