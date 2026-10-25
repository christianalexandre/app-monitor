package appmonitor.desktop.mcp

import appmonitor.desktop.mcp.Filters.putFilterProperties
import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.ExchangeSizes
import appmonitor.desktop.storage.Storage
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.ceil

/** `endpoint_stats` (SDD 10): the exchanges a filter matches, grouped by method, host and path. */
internal object EndpointStats {
    const val NAME = "endpoint_stats"
    private const val MAX_LIMIT = 500L
    private const val DEFAULT_LIMIT = 50L
    const val MAX_EXCHANGES = 100_000L

    val description =
        "Per endpoint (method, host and path without query; http/https and ws/wss together; no path templating) of the " +
            "stored exchanges a filter matches, most called first: count, status classes, error rate (status 400 and up " +
            "or failed), duration percentiles and stored body bytes. Reads at most $MAX_EXCHANGES exchanges, oldest " +
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

    /** Reads up to [maxExchanges] exchanges [filter] matches by page and groups them, most called first. */
    suspend fun group(
        storage: Storage,
        filter: ExchangeFilter,
        maxExchanges: Long = MAX_EXCHANGES,
    ): Grouped {
        val endpoints = LinkedHashMap<Endpoint, Tally>()
        var read = 0L
        var afterId = 0L
        var truncated = false
        while (true) {
            if (read == maxExchanges) {
                truncated = storage.searchExchangeSizes(filter, afterId, 1).isNotEmpty()
                break
            }
            val page = storage.searchExchangeSizes(filter, afterId, minOf(Storage.PAGE_SIZE, maxExchanges - read))
            page.forEach { endpoints.getOrPut(endpointOf(it)) { Tally() }.add(it) }
            read += page.size
            if (page.isEmpty()) break
            afterId = page.last().exchange.id
        }
        if (storage.isClosing) throw ToolError(CLOSING)
        val sorted =
            endpoints.entries
                .map { (endpoint, tally) -> Stats(endpoint, tally) }
                .sortedWith(compareByDescending<Stats> { it.tally.count }.thenBy { it.endpoint.toString() })
        return Grouped(sorted, read, truncated)
    }

    class Grouped(
        val endpoints: List<Stats>,
        val exchangesRead: Long,
        val truncated: Boolean,
    )

    class Stats(
        val endpoint: Endpoint,
        val tally: Tally,
    ) {
        fun toJson(): JsonObject =
            buildJsonObject {
                put("method", endpoint.method.json())
                put("host", endpoint.host.json())
                put("path", endpoint.path)
                put("count", tally.count)
                putJsonObject("status") {
                    tally.classes.forEachIndexed { index, n -> put("${index + 1}xx", n) }
                    put("failed", tally.failed)
                }
                put("error_rate", tally.errorRate)
                val durations = tally.durations()
                if (durations.isEmpty()) {
                    put("duration_ms", JsonNull)
                } else {
                    putJsonObject("duration_ms") {
                        put("min", durations.first())
                        put("p50", durations.percentile(50))
                        put("p95", durations.percentile(95))
                        put("max", durations.last())
                    }
                }
                put("request_bytes", tally.requestBytes)
                put("response_bytes", tally.responseBytes)
            }
    }

    data class Endpoint(
        val method: String?,
        val host: String?,
        val path: String,
    )

    class Tally {
        var count = 0L
            private set
        var failed = 0L
            private set
        var errors = 0L
            private set
        var requestBytes = 0L
            private set
        var responseBytes = 0L
            private set
        val classes = LongArray(5)
        private val durations = ArrayList<Long>()

        val errorRate: Double get() = if (count == 0L) 0.0 else errors.toDouble() / count

        fun add(row: ExchangeSizes) {
            val exchange = row.exchange
            count++
            exchange.statusCode?.let { status -> if (status in 100..599) classes[status / 100 - 1]++ }
            if (exchange.failed) failed++
            if (exchange.failed || (exchange.statusCode ?: 0) >= 400) errors++
            val start = exchange.startedAtMs
            val end = exchange.endedAtMs
            if (start != null && end != null) durations += end - start
            requestBytes += row.requestBytes
            responseBytes += row.responseBytes
        }

        /** The durations of the exchanges with both times, ascending. */
        fun durations(): List<Long> = durations.sorted()
    }

    /** Method, lower-cased host and the URL's path without scheme, query or fragment; `/` when it has none. */
    fun endpointOf(row: ExchangeSizes): Endpoint {
        val exchange = row.exchange
        val url = exchange.url.orEmpty()
        val hostAndPath = url.substringAfter("://", url).substringBefore('?').substringBefore('#')
        val slash = hostAndPath.indexOf('/')
        val path = if (slash < 0) "/" else hostAndPath.substring(slash)
        return Endpoint(exchange.method, exchange.host?.lowercase(), path)
    }
}

/** Nearest rank: the smallest value with at least [percent] % of the list at or below it; the list is sorted. */
internal fun List<Long>.percentile(percent: Int): Long = this[(ceil(size * percent / 100.0).toInt() - 1).coerceIn(indices)]
