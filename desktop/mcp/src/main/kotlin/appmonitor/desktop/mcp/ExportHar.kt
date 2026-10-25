package appmonitor.desktop.mcp

import appmonitor.desktop.mcp.Filters.putFilterProperties
import appmonitor.desktop.storage.ExchangeDetail
import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StoredFrame
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

    val description =
        "Exports the stored exchanges a filter matches, oldest first, as a HAR 1.2 log (the result text is the HAR " +
            "file): headers, bodies (bodies: none, capped at $CAP bytes each, or full) and WebSocket frames in " +
            "_webSocketMessages. Meant to be saved as a file; search_requests and get_request suit reading. $DATA_NOTE"

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
                        put("description", "Exchanges exported; log.comment says when more matched.")
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
        storage: Storage,
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
        return log(storage, filter, maxEntries, cap, version, maxText)
    }

    /** The first [maxEntries] exchanges [filter] matches as a HAR 1.2 log, each body and payload cut at [cap] bytes. */
    suspend fun log(
        storage: Storage,
        filter: ExchangeFilter,
        maxEntries: Long,
        cap: Long,
        version: String,
        maxText: Long = MAX_TEXT,
    ): JsonObject {
        val rows = storage.searchExchanges(filter, 0, maxEntries + 1)
        var text = 0L
        val entries = mutableListOf<JsonObject>()
        for (row in rows.take(maxEntries.toInt())) {
            val detail = storage.exchange(row.id) ?: continue
            val entry = entry(detail, allFrames(storage, row.id, row.frameCount), cap)
            text += entry.toString().length
            if (text > maxText) throw ToolError("the HAR is past ${maxText / 1024 / 1024} MiB: narrow the filter or use bodies: none")
            entries += entry
        }
        return buildJsonObject {
            putJsonObject("log") {
                put("version", "1.2")
                putJsonObject("creator") {
                    put("name", "App Monitor")
                    put("version", version)
                }
                put("entries", JsonArray(entries))
                if (rows.size > maxEntries) put("comment", "first $maxEntries matching exchanges; more matched")
            }
        }
    }

    private suspend fun allFrames(
        storage: Storage,
        exchangeId: Long,
        count: Long,
    ): List<StoredFrame> {
        if (count == 0L) return emptyList()
        val frames = mutableListOf<StoredFrame>()
        while (true) {
            val page = storage.frames(exchangeId, frames.lastOrNull()?.id ?: 0, Storage.PAGE_SIZE)
            frames += page
            if (page.size < Storage.PAGE_SIZE) return frames
        }
    }

    private fun entry(
        detail: ExchangeDetail,
        frames: List<StoredFrame>,
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
                put("headers", request?.headers.orEmpty().toHar())
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
                put("headers", response?.headers.orEmpty().toHar())
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
            if (frames.isNotEmpty()) {
                put(
                    "_webSocketMessages",
                    buildJsonArray {
                        frames.forEach { frame ->
                            add(
                                buildJsonObject {
                                    put("type", if (frame.direction == WebSocketFrame.Direction.DIRECTION_SENT) "send" else "receive")
                                    put("time", frame.atMs / 1000.0)
                                    put("opcode", rfcOpcode(frame.opcode))
                                    // HAR viewers read a binary frame's data (opcode 2) as base64.
                                    val slice = slice(frame.payload, 0, cap)
                                    put("data", slice.text ?: slice.bytes.base64())
                                    if (cap > 0) {
                                        payloadComment(
                                            frame.payload,
                                            frame.payloadSize,
                                            frame.payloadTruncated,
                                            slice,
                                        )?.let { put("comment", it) }
                                    }
                                },
                            )
                        }
                    },
                )
            }
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

    private fun List<Header>.toHar() =
        JsonArray(
            map {
                buildJsonObject {
                    put("name", it.name)
                    put("value", it.value_)
                }
            },
        )
}
