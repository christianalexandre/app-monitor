package appmonitor.desktop.mcp

import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StoredFrame
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** `get_request` (SDD 10): one exchange with its headers, bodies capped per ADR-0089 B.1, and a page of its frames. */
internal object GetRequest {
    const val NAME = "get_request"
    private val parts = listOf("request", "response", "both")
    private const val MAX_BODY = 262_144L
    private const val DEFAULT_BODY = 16_384L
    private const val MAX_FRAMES = 200L
    private const val DEFAULT_FRAMES = 20L

    val description =
        "Returns one stored exchange by the id search_requests gives: its row, request and response headers and " +
            "bodies, and a page of its WebSocket frames. A body is UTF-8 text or base64, at most body_max_bytes from " +
            "body_offset; truncated_by_tool says more is stored, truncated_by_device that the app sent less than the " +
            "original. Page frames with frames.next_after_id. A connection (capture_layer connection) has no request or " +
            "response, only connection.sent_bytes and received_bytes, null until it closed. A mocked exchange (mock_rule_id " +
            "set) was answered on the device by that mock rule, not the network; mock_rule_name is null once the rule is " +
            "deleted. $DATA_NOTE"

    val inputSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "The exchange's id.")
                    }
                    putJsonObject("part") {
                        put("type", "string")
                        putJsonArray("enum") { parts.forEach { add(JsonPrimitive(it)) } }
                        put("default", "both")
                        put("description", "Which side to return.")
                    }
                    putJsonObject("body_offset") {
                        put("type", "integer")
                        put("minimum", 0)
                        put("default", 0)
                        put("description", "First stored byte of each body to return.")
                    }
                    putJsonObject("body_max_bytes") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", MAX_BODY)
                        put("default", DEFAULT_BODY)
                        put("description", "Bytes per body and per frame payload, and the frames page's byte budget.")
                    }
                    putJsonObject("frames_after_id") {
                        put("type", "integer")
                        put("description", "The frames.next_after_id of the previous call.")
                    }
                    putJsonObject("frames_limit") {
                        put("type", "integer")
                        put("minimum", 0)
                        put("maximum", MAX_FRAMES)
                        put("default", DEFAULT_FRAMES)
                        put("description", "Frames per page; 0 reads none.")
                    }
                },
            required = listOf("id"),
        )

    suspend fun call(
        storage: Storage,
        arguments: JsonObject?,
    ): JsonObject {
        val id = arguments.long("id", 1..Long.MAX_VALUE, null) ?: throw ToolError("id is required")
        val part = arguments.choice("part", parts, "both")!!
        val offset = arguments.long("body_offset", 0..Long.MAX_VALUE, 0)!!
        val max = arguments.long("body_max_bytes", 1..MAX_BODY, DEFAULT_BODY)!!
        val framesAfter = arguments.long("frames_after_id", 0..Long.MAX_VALUE, 0)!!
        val framesLimit = arguments.long("frames_limit", 0..MAX_FRAMES, DEFAULT_FRAMES)!!
        val detail = storage.exchange(id)
        val frames = if (detail != null && framesLimit > 0) storage.frames(id, framesAfter, framesLimit) else emptyList()
        if (detail == null) throw ToolError("no exchange with id $id")
        val connection = detail.connectionBytes
        return buildJsonObject {
            putExchangeRow(detail.exchange)
            put("mock_rule_name", detail.mockRuleName.json())
            put(
                "connection",
                connection?.let {
                    buildJsonObject {
                        put("sent_bytes", it.sent.json())
                        put("received_bytes", it.received.json())
                    }
                } ?: JsonNull,
            )
            val request = detail.request?.takeIf { part != "response" && connection == null }
            put(
                "request",
                request?.let {
                    buildJsonObject {
                        put("method", it.method)
                        put("url", it.url)
                        put("headers", it.headers.toJson())
                        put("body", bodyJson(it.body, it.body_size, it.body_truncated, it.headers.contentType(), offset, max))
                    }
                } ?: JsonNull,
            )
            val response = detail.response?.takeIf { part != "request" && connection == null }
            put(
                "response",
                response?.let {
                    buildJsonObject {
                        put("status_code", it.status_code)
                        put("headers", it.headers.toJson())
                        put("body", bodyJson(it.body, it.body_size, it.body_truncated, it.headers.contentType(), offset, max))
                    }
                } ?: JsonNull,
            )
            put("frames", framesJson(frames, framesLimit, max))
        }
    }

    // The page stops at framesLimit, or once its payloads reach max bytes after at least one frame.
    private fun framesJson(
        frames: List<StoredFrame>,
        limit: Long,
        max: Long,
    ): JsonObject {
        var bytes = 0L
        val page = mutableListOf<JsonObject>()
        for (frame in frames) {
            if (page.isNotEmpty() && bytes >= max) break
            val payload = bodyJson(frame.payload, frame.payloadSize, frame.payloadTruncated, null, 0, max)
            bytes += minOf(frame.payload.size.toLong(), max)
            page +=
                buildJsonObject {
                    put("id", frame.id)
                    put("at", time(frame.atMs))
                    put(
                        "direction",
                        frame.direction.wireName("DIRECTION_"),
                    )
                    put(
                        "opcode",
                        frame.opcode.wireName("OPCODE_"),
                    )
                    put("payload", payload)
                }
        }
        val full = limit > 0 && (page.size < frames.size || frames.size.toLong() == limit)
        return buildJsonObject {
            put("items", JsonArray(page))
            put("next_after_id", (if (full) page.last()["id"] else null) ?: JsonNull)
        }
    }
}
