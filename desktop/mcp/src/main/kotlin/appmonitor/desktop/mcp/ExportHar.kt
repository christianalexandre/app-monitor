package appmonitor.desktop.mcp

import appmonitor.desktop.domain.CapturedFrame
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeDetail
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.TrafficQueryRepository
import appmonitor.desktop.domain.TrafficRepository.Companion.PAGE_SIZE
import appmonitor.desktop.mcp.Filters.putFilterProperties
import appmonitor.v1.Header
import appmonitor.v1.WebSocketFrame
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.ByteString
import java.net.URI
import java.net.URLDecoder

/** `export_har` (SDD 10): the exchanges a filter matches, oldest first, as one HAR 1.2 log. */
internal object ExportHar {
    const val NAME = "export_har"
    private val bodyModes = listOf("none", "capped", "full")
    const val MAX_ENTRIES = 1_000L
    private const val DEFAULT_ENTRIES = 100L
    const val CAP = 16_384L
    const val FULL = Int.MAX_VALUE.toLong()
    const val MAX_TEXT = 32L * 1024 * 1024

    /** Matching rows an export reads at most, the live list's bound: a filter matching mostly connections stops there. */
    const val MAX_SCANNED = 10_000L

    val description =
        "Exports the stored exchanges a filter matches, oldest first, as a HAR 1.2 log (the result text is the HAR " +
            "file): headers, bodies (bodies: none, capped at $CAP bytes each, or full) and WebSocket frames in " +
            "_webSocketMessages. Connections are left out, counted in log.comment. Meant to be saved as a file; " +
            "search_requests and get_request suit reading. $DATA_NOTE"

    val inputSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putFilterProperties()
                    putJsonObject("max_entries") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", MAX_ENTRIES)
                        put("default", DEFAULT_ENTRIES)
                        put("description", "HTTP exchanges exported, connections not counted; log.comment says when more matched.")
                    }
                    putJsonObject("bodies") {
                        put("type", "string")
                        putJsonArray("enum") { bodyModes.forEach { add(JsonPrimitive(it)) } }
                        put("default", "capped")
                        put("description", "Bodies and frame payloads: none, the first $CAP bytes of each, or whole.")
                    }
                },
        )

    suspend fun call(
        queries: TrafficQueryRepository,
        arguments: JsonObject?,
        version: String,
        maxText: Long = MAX_TEXT,
    ): JsonObject {
        val filter = Filters.filter(arguments)
        val maxEntries = arguments.long("max_entries", 1..MAX_ENTRIES, DEFAULT_ENTRIES)!!
        val cap =
            when (arguments.choice("bodies", bodyModes, "capped")) {
                "none" -> 0L
                "capped" -> CAP
                else -> FULL
            }
        return log(queries, filter, maxEntries, cap, version, maxText).json
    }

    /** The first [maxEntries] exchanges [filter] matches as a HAR 1.2 log, each body and payload cut at [cap] bytes. */
    suspend fun log(
        queries: TrafficQueryRepository,
        filter: ExchangeFilter,
        maxEntries: Long,
        cap: Long,
        version: String,
        maxText: Long = MAX_TEXT,
        maxScanned: Long = MAX_SCANNED,
    ): HarLog {
        val (http, connections, cut) = httpRows(queries, filter, maxEntries, maxScanned)
        var text = 0L
        val entries = mutableListOf<JsonObject>()
        val tooLarge = { ToolError("the HAR is past ${maxText / 1024 / 1024} MiB: narrow the filter or use bodies: none") }
        for (row in http.take(maxEntries.toInt())) {
            val detail = queries.detail(row.id) ?: continue
            val messages = webSocketMessages(queries, row.id, row.frameCount, cap) { if (text + it > maxText) throw tooLarge() }
            val entry = entry(detail, messages, cap)
            text += entry.toString().encodeToByteArray().size
            if (text > maxText) throw tooLarge()
            entries += entry
        }
        val more = cut || http.size > maxEntries
        val json =
            buildJsonObject {
                putJsonObject("log") {
                    put("version", "1.2")
                    putJsonObject("creator") {
                        put("name", "App Monitor")
                        put("version", version)
                    }
                    put("entries", JsonArray(entries))
                    listOfNotNull(
                        when {
                            cut -> "stopped after $maxScanned matching exchanges; more may match: narrow the filter"
                            more -> "first $maxEntries matching exchanges; more matched"
                            else -> null
                        },
                        if (connections > 0) "$connections connections left out: HAR holds HTTP exchanges only" else null,
                    ).joinToString("; ").ifEmpty { null }?.let { put("comment", it) }
                }
            }
        return HarLog(json, entries.size, more, connections)
    }

    /** HTTP rows read for an export, the connections before its last entry (ADR-0104), and whether the scan stopped. */
    private data class Rows(
        val http: List<Exchange>,
        val connections: Int,
        val cut: Boolean,
    )

    /** The first [exported] + 1 HTTP exchanges [filter] matches, reading at most [maxScanned] matching rows. */
    private suspend fun httpRows(
        queries: TrafficQueryRepository,
        filter: ExchangeFilter,
        exported: Long,
        maxScanned: Long,
    ): Rows {
        val http = mutableListOf<Exchange>()
        var connections = 0
        var inSpan = 0
        var scanned = 0L
        var afterId = 0L
        while (http.size <= exported) {
            val page = queries.search(filter, afterId, PAGE_SIZE)
            for (row in page) {
                if (http.size > exported) break
                if (scanned++ == maxScanned) return Rows(http, connections, cut = true)
                if (row.isConnection) {
                    connections++
                } else {
                    http += row
                    if (http.size.toLong() == exported) inSpan = connections
                }
            }
            if (page.size < PAGE_SIZE) break
            afterId = page.last().id
        }
        return Rows(http, if (http.size > exported) inSpan else connections, cut = false)
    }

    /** The frames as `_webSocketMessages`, a page at a time; [check] gets their size so far after each page. */
    private suspend fun webSocketMessages(
        queries: TrafficQueryRepository,
        exchangeId: Long,
        count: Long,
        cap: Long,
        check: (Long) -> Unit,
    ): List<JsonObject> {
        if (count == 0L) return emptyList()
        val messages = mutableListOf<JsonObject>()
        var size = 0L
        var afterId = 0L
        while (true) {
            val page = queries.frames(exchangeId, afterId, PAGE_SIZE)
            for (frame in page) {
                messages += message(frame, cap).also { size += it.toString().encodeToByteArray().size }
            }
            check(size)
            if (page.size < PAGE_SIZE) return messages
            afterId = page.last().id
        }
    }

    private fun message(
        frame: CapturedFrame,
        cap: Long,
    ): JsonObject =
        buildJsonObject {
            put("type", if (frame.direction == WebSocketFrame.Direction.DIRECTION_SENT) "send" else "receive")
            put("time", frame.atMs / 1000.0)
            put("opcode", rfcOpcode(frame.opcode))
            // HAR viewers read a binary frame's data (opcode 2) as base64.
            val slice = slice(frame.payload.bytes, 0, cap)
            put("data", slice.text ?: slice.bytes.base64())
            if (cap > 0) {
                payloadComment(
                    frame.payload.bytes,
                    frame.payload.originalSize,
                    frame.payload.truncated,
                    slice,
                )?.let { put("comment", it) }
            }
        }

    private fun entry(
        detail: ExchangeDetail,
        messages: List<JsonObject>,
        cap: Long,
    ): JsonObject {
        val row = detail.exchange
        val request = detail.request
        val response = detail.response
        val duration = row.startedAtMs?.let { start -> row.endedAtMs?.let { maxOf(it - start, 0) } }
        val url = request?.url ?: row.url ?: ""
        return buildJsonObject {
            put("startedDateTime", time(row.startedAtMs ?: 0))
            put("time", duration ?: 0)
            putJsonObject("request") {
                put("method", request?.method ?: row.method ?: "")
                put("url", url)
                put("httpVersion", "unknown")
                putJsonArray("cookies") {}
                put("headers", request?.headers.orEmpty().toJson())
                put("queryString", queryString(url))
                if (request != null && (request.body.size > 0 || request.body_truncated)) {
                    putJsonObject("postData") {
                        put("mimeType", request.headers.contentType() ?: "")
                        putBody(request.body, request.body_size, request.body_truncated, cap, binaryField = "_encoding")
                    }
                }
                put("headersSize", -1)
                put("bodySize", request?.let { it.body_size ?: it.body.size.toLong() } ?: 0)
            }
            putJsonObject("response") {
                put("status", response?.status_code ?: 0)
                put("statusText", "")
                put("httpVersion", "unknown")
                putJsonArray("cookies") {}
                put("headers", response?.headers.orEmpty().toJson())
                putJsonObject("content") {
                    put("size", response?.let { it.body_size ?: it.body.size.toLong() } ?: 0)
                    put("mimeType", response?.headers?.contentType() ?: "")
                    if (response != null && (response.body.size > 0 || response.body_truncated)) {
                        putBody(response.body, response.body_size, response.body_truncated, cap, binaryField = "encoding")
                    }
                }
                put("redirectURL", "")
                put("headersSize", -1)
                put("bodySize", -1)
            }
            putJsonObject("cache") {}
            putJsonObject("timings") {
                put("send", 0)
                put("wait", duration ?: 0)
                put("receive", 0)
            }
            when {
                row.failed -> put("comment", "failed: ${row.error ?: "no response"}")
                duration == null -> put("comment", "no end time stored")
            }
            if (messages.isNotEmpty()) put("_webSocketMessages", JsonArray(messages))
        }
    }

    // A text body as `text`; a binary one as base64 `text` with [binaryField] "base64" (HAR's `encoding` exists in content only).
    private fun JsonObjectBuilder.putBody(
        stored: ByteString,
        originalSize: Long?,
        truncatedByDevice: Boolean,
        cap: Long,
        binaryField: String,
    ) {
        if (cap == 0L) return
        val slice = slice(stored, 0, cap)
        if (slice.text != null) {
            put("text", slice.text)
        } else {
            put("text", slice.bytes.base64())
            put(binaryField, "base64")
        }
        payloadComment(stored, originalSize, truncatedByDevice, slice)?.let { put("comment", it) }
    }

    private fun payloadComment(
        stored: ByteString,
        originalSize: Long?,
        truncatedByDevice: Boolean,
        slice: BodySlice,
    ): String? {
        val parts =
            listOfNotNull(
                if (truncatedByDevice) "truncated by the device: ${stored.size} of ${originalSize ?: "unknown"} bytes stored" else null,
                if (slice.bytes.size < stored.size) "truncated by the export: ${slice.bytes.size} of ${stored.size} stored bytes" else null,
            )
        return parts.joinToString("; ").ifEmpty { null }
    }

    private fun rfcOpcode(opcode: WebSocketFrame.Opcode) =
        when (opcode) {
            WebSocketFrame.Opcode.OPCODE_TEXT -> 1
            WebSocketFrame.Opcode.OPCODE_BINARY -> 2
            WebSocketFrame.Opcode.OPCODE_CLOSE -> 8
            WebSocketFrame.Opcode.OPCODE_PING -> 9
            WebSocketFrame.Opcode.OPCODE_PONG -> 10
            else -> 0
        }

    private fun queryString(url: String): JsonArray {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: url.substringAfter('?', "").substringBefore('#')
        return buildJsonArray {
            query.split('&').filter { it.isNotEmpty() }.forEach { pair ->
                add(
                    buildJsonObject {
                        put("name", decode(pair.substringBefore('=')))
                        put("value", decode(pair.substringAfter('=', "")))
                    },
                )
            }
        }
    }

    private fun decode(part: String) = runCatching { URLDecoder.decode(part, Charsets.UTF_8) }.getOrDefault(part)
}

/** A HAR log's [json], the [entries] it holds, whether [more] matched, and the [connections] left out of it. */
internal class HarLog(
    val json: JsonObject,
    val entries: Int,
    val more: Boolean,
    val connections: Int,
)
