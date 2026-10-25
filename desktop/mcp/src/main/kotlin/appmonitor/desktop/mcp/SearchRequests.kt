package appmonitor.desktop.mcp

import appmonitor.desktop.mcp.Filters.putFilterProperties
import appmonitor.desktop.storage.Storage
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** `search_requests` (SDD 10): the exchanges a filter matches, oldest first, without headers or bodies (ADR-0089 B.1). */
internal object SearchRequests {
    const val NAME = "search_requests"
    private const val MAX_LIMIT = 200L
    private const val DEFAULT_LIMIT = 50L

    val description =
        "Searches the stored HTTP and WebSocket exchanges, oldest first: method, URL, status, failure, timing, capture " +
            "layer and frame count, no headers or bodies (get_request has them). Unset filters match all. Page with " +
            "next_after_id. $DATA_NOTE"

    val inputSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putFilterProperties()
                    putJsonObject("after_id") {
                        put("type", "integer")
                        put("description", "The next_after_id of the previous page.")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", MAX_LIMIT)
                        put("default", DEFAULT_LIMIT)
                        put("description", "Exchanges per page.")
                    }
                },
        )

    suspend fun call(
        storage: Storage,
        arguments: JsonObject?,
    ): JsonObject {
        val filter = Filters.filter(arguments)
        val afterId = arguments.long("after_id", 0..Long.MAX_VALUE, 0)!!
        val limit = arguments.long("limit", 1..MAX_LIMIT, DEFAULT_LIMIT)!!
        val exchanges = storage.searchExchanges(filter, afterId, limit)
        if (storage.isClosing) throw ToolError(CLOSING)
        return buildJsonObject {
            put("requests", JsonArray(exchanges.map { it.toRowJson() }))
            put("next_after_id", (if (exchanges.size.toLong() == limit) exchanges.last().id else null).json())
        }
    }
}
