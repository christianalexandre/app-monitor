package appmonitor.desktop.mcp

import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.StatusClass
import appmonitor.desktop.storage.StoredExchange
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The filter fields shared by the tools that read many exchanges, one to one onto [ExchangeFilter] (SDD 10). */
internal object Filters {
    private val statusClasses = StatusClass.entries.associateBy { "${it.from / 100}xx" }

    fun JsonObjectBuilder.putFilterProperties() {
        putJsonObject("text") {
            put("type", "string")
            put("description", "Substring of the URL, host included, ASCII case-insensitive.")
        }
        putJsonObject("method") {
            put("type", "string")
            put("description", "HTTP method exactly as sent, e.g. GET.")
        }
        putJsonObject("status_class") {
            put("type", "string")
            putJsonArray("enum") { statusClasses.keys.forEach { add(JsonPrimitive(it)) } }
            put("description", "Status code's hundreds.")
        }
        putJsonObject("failed_only") {
            put("type", "boolean")
            put("default", false)
            put("description", "Only the exchanges that failed without a response (transport error).")
        }
        putJsonObject("device_id") {
            put("type", "string")
            put("description", "A session's device_id, as list_runs shows it.")
        }
        putJsonObject("app_id") {
            put("type", "string")
            put("description", "A session's app_id, as list_runs shows it.")
        }
        putJsonObject("run_id") {
            put("type", "integer")
            put("description", "A run's id, as list_runs shows it.")
        }
        putJsonObject("session_id") {
            put("type", "integer")
            put("description", "A session's session_id, as list_runs shows it.")
        }
    }

    fun filter(arguments: JsonObject?) =
        ExchangeFilter(
            text = arguments.string("text"),
            method = arguments.string("method"),
            statusClass = arguments.choice("status_class", statusClasses.keys, null)?.let { statusClasses.getValue(it) },
            failedOnly = arguments.boolean("failed_only", false),
            deviceId = arguments.string("device_id"),
            appId = arguments.string("app_id"),
            runId = arguments.long("run_id", 1..Long.MAX_VALUE, null),
            sessionId = arguments.long("session_id", 1..Long.MAX_VALUE, null),
        )
}

/** The row fields of an exchange, the same in `search_requests` and `get_request`. */
internal fun JsonObjectBuilder.putExchangeRow(exchange: StoredExchange) {
    put("id", exchange.id)
    put("session_id", exchange.sessionId)
    put("method", exchange.method.json())
    put("url", exchange.url.json())
    put("status_code", exchange.statusCode?.toLong().json())
    put("grpc_status", exchange.grpcStatus?.toLong().json())
    put("failed", exchange.failed)
    put("error", exchange.error.json())
    put("started_at", time(exchange.startedAtMs))
    val duration = exchange.startedAtMs?.let { start -> exchange.endedAtMs?.let { it - start } }
    put("duration_ms", duration.json())
    put(
        "capture_layer",
        exchange.captureLayer.wireName("CAPTURE_LAYER_"),
    )
    put("frame_count", exchange.frameCount)
}

internal fun StoredExchange.toRowJson(): JsonObject = buildJsonObject { putExchangeRow(this@toRowJson) }
