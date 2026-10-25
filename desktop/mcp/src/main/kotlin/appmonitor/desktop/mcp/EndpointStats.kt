package appmonitor.desktop.mcp

import appmonitor.desktop.mcp.Filters.putFilterProperties
import appmonitor.desktop.storage.EndpointGroups
import appmonitor.desktop.storage.EndpointTally
import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.MAX_GROUPED_EXCHANGES
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.groupByEndpoint
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** `endpoint_stats` (SDD 10): the exchanges a filter matches, grouped by method, host and path. */
internal object EndpointStats {
    const val NAME = "endpoint_stats"
    private const val MAX_LIMIT = 500L
    private const val DEFAULT_LIMIT = 50L
    const val MAX_EXCHANGES = MAX_GROUPED_EXCHANGES

    val description =
        "Per endpoint (method, host and path without query; http/https and ws/wss together; no path templating; a " +
            "connection's path is :port) of the stored exchanges a filter matches, most called first: count, status " +
            "classes, error rate (status 400 and up, failed, or a grpc-status other than 0), duration percentiles and " +
            "stored body bytes (a connection's bytes sent and received). Reads at most $MAX_EXCHANGES exchanges, oldest " +
            "first; truncated says it stopped there. Unset filters match all. $DATA_NOTE"

    val inputSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putFilterProperties()
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", MAX_LIMIT)
                        put("default", DEFAULT_LIMIT)
                        put("description", "Endpoints returned, most called first.")
                    }
                },
        )

    suspend fun call(
        storage: Storage,
        arguments: JsonObject?,
        maxExchanges: Long = MAX_EXCHANGES,
    ): JsonObject {
        val filter = Filters.filter(arguments)
        val limit = arguments.long("limit", 1..MAX_LIMIT, DEFAULT_LIMIT)!!
        val grouped = group(storage, filter, maxExchanges)
        return buildJsonObject {
            put("endpoints", JsonArray(grouped.endpoints.take(limit.toInt()).map { it.toJson() }))
            put("endpoint_count", grouped.endpoints.size)
            put("exchanges_read", grouped.exchangesRead)
            put("truncated", grouped.truncated)
        }
    }

    /** [groupByEndpoint], an error once the store closes: a read then may have stopped early. */
    suspend fun group(
        storage: Storage,
        filter: ExchangeFilter,
        maxExchanges: Long = MAX_EXCHANGES,
    ): EndpointGroups {
        val groups = storage.groupByEndpoint(filter, maxExchanges)
        if (storage.isClosing) throw ToolError(CLOSING)
        return groups
    }

    fun EndpointTally.toJson(): JsonObject =
        buildJsonObject {
            put("method", endpoint.method.json())
            put("host", endpoint.host.json())
            put("path", endpoint.path)
            put("count", count)
            putJsonObject("status") {
                statusClasses.forEachIndexed { index, n -> put("${index + 1}xx", n) }
                put("failed", failed)
            }
            put("error_rate", errorRate)
            val durations = durationStats
            if (durations == null) {
                put("duration_ms", JsonNull)
            } else {
                putJsonObject("duration_ms") {
                    put("min", durations.min)
                    put("p50", durations.p50)
                    put("p95", durations.p95)
                    put("p99", durations.p99)
                    put("max", durations.max)
                }
            }
            put("request_bytes", requestBytes)
            put("response_bytes", responseBytes)
        }
}
