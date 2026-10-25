package appmonitor.desktop.mcp

import appmonitor.desktop.mcp.EndpointStats.Endpoint
import appmonitor.desktop.mcp.EndpointStats.Stats
import appmonitor.desktop.mcp.Filters.putFilterProperties
import appmonitor.desktop.storage.Storage
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs

/** `diff_runs` (SDD 10): `endpoint_stats`' grouping of two runs, compared per endpoint. */
internal object DiffRuns {
    const val NAME = "diff_runs"
    private const val MAX_LIMIT = 500L
    private const val DEFAULT_LIMIT = 50L
    private const val MIN_MOVE_MS = 10L
    private const val MIN_MOVE_PERCENT = 20L

    val description =
        "Compares two runs per endpoint (endpoint_stats' grouping): endpoints added in run_id, gone from it, and " +
            "changed (count or error rate differs, or p50 or p95 duration moved by at least $MIN_MOVE_PERCENT % and " +
            "$MIN_MOVE_MS ms), largest error rate change first; the rest is counted as unchanged. The other filters " +
            "apply to both runs. $DATA_NOTE"

    val inputSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putJsonObject("base_run_id") {
                        put("type", "integer")
                        put("description", "The run compared against, as list_runs shows it.")
                    }
                    putFilterProperties()
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", MAX_LIMIT)
                        put("default", DEFAULT_LIMIT)
                        put("description", "Endpoints returned in each of added, gone and changed.")
                    }
                },
            required = listOf("base_run_id", "run_id"),
        )

    suspend fun call(
        storage: Storage,
        arguments: JsonObject?,
        maxExchanges: Long = EndpointStats.MAX_EXCHANGES,
    ): JsonObject {
        val baseRunId = arguments.long("base_run_id", 1..Long.MAX_VALUE, null) ?: throw ToolError("base_run_id is required")
        val filter = Filters.filter(arguments)
        val runId = filter.runId ?: throw ToolError("run_id is required")
        val limit = arguments.long("limit", 1..MAX_LIMIT, DEFAULT_LIMIT)!!.toInt()
        if (storage.isClosing) throw ToolError(CLOSING)
        val known = storage.runs().map { it.id }.toSet()
        listOf(baseRunId, runId).firstOrNull { it !in known }?.let { throw ToolError("no run with id $it") }

        val base = EndpointStats.group(storage, filter.copy(runId = baseRunId), maxExchanges)
        val run = EndpointStats.group(storage, filter, maxExchanges)
        val baseByEndpoint = base.endpoints.associateBy { it.endpoint }
        val runByEndpoint = run.endpoints.associateBy { it.endpoint }
        val added = run.endpoints.filter { it.endpoint !in baseByEndpoint }
        val gone = base.endpoints.filter { it.endpoint !in runByEndpoint }
        val (changed, unchanged) =
            run.endpoints
                .mapNotNull { stats -> baseByEndpoint[stats.endpoint]?.let { Change(stats.endpoint, Numbers(it), Numbers(stats)) } }
                .partition { it.isChange }
        val sorted =
            changed.sortedWith(
                compareByDescending<Change> { abs(it.run.errorRate - it.base.errorRate) }
                    .thenByDescending { abs((it.run.p95 ?: 0) - (it.base.p95 ?: 0)) }
                    .thenBy { it.endpoint.toString() },
            )
        return buildJsonObject {
            put("base_run_id", baseRunId)
            put("run_id", runId)
            put("added", JsonArray(added.take(limit).map(Stats::toJson)))
            put("added_count", added.size)
            put("gone", JsonArray(gone.take(limit).map(Stats::toJson)))
            put("gone_count", gone.size)
            putJsonArray("changed") { sorted.take(limit).forEach { add(it.toJson()) } }
            put("changed_count", changed.size)
            put("unchanged", unchanged.size)
            put("truncated", base.truncated || run.truncated)
        }
    }

    /** The numbers `diff_runs` compares for one endpoint of one run. */
    private class Numbers(
        stats: Stats,
    ) {
        val count = stats.tally.count
        val errorRate = stats.tally.errorRate
        private val durations = stats.tally.durations()
        val p50 = durations.takeIf { it.isNotEmpty() }?.percentile(50)
        val p95 = durations.takeIf { it.isNotEmpty() }?.percentile(95)
    }

    private class Change(
        val endpoint: Endpoint,
        val base: Numbers,
        val run: Numbers,
    ) {
        val isChange =
            base.count != run.count || base.errorRate != run.errorRate || moved(base.p50, run.p50) || moved(base.p95, run.p95)

        fun toJson(): JsonObject =
            buildJsonObject {
                put("method", endpoint.method.json())
                put("host", endpoint.host.json())
                put("path", endpoint.path)
                compare("count", JsonPrimitive(base.count), JsonPrimitive(run.count), JsonPrimitive(run.count - base.count))
                compare(
                    "error_rate",
                    JsonPrimitive(base.errorRate),
                    JsonPrimitive(run.errorRate),
                    JsonPrimitive(run.errorRate - base.errorRate),
                )
                compare("p50_ms", base.p50.json(), run.p50.json(), delta(base.p50, run.p50))
                compare("p95_ms", base.p95.json(), run.p95.json(), delta(base.p95, run.p95))
            }

        private fun JsonObjectBuilder.compare(
            name: String,
            base: JsonElement,
            run: JsonElement,
            delta: JsonElement,
        ) = putJsonObject(name) {
            put("base", base)
            put("run", run)
            put("delta", delta)
        }

        private fun delta(
            base: Long?,
            run: Long?,
        ): JsonElement = if (base == null || run == null) JsonNull else JsonPrimitive(run - base)
    }

    /** A duration known on one side only moved; known on both, it moved by at least 10 ms and 20 % of [base]. */
    private fun moved(
        base: Long?,
        run: Long?,
    ): Boolean {
        if (base == null || run == null) return base != run
        val move = abs(run - base)
        return move >= MIN_MOVE_MS && move * 100 >= base * MIN_MOVE_PERCENT
    }
}
