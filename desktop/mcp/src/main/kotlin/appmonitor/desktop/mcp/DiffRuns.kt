package appmonitor.desktop.mcp

import appmonitor.desktop.mcp.EndpointStats.toJson
import appmonitor.desktop.mcp.Filters.putFilterProperties
import appmonitor.desktop.storage.EndpointChange
import appmonitor.desktop.storage.MIN_MOVE_MS
import appmonitor.desktop.storage.MIN_MOVE_PERCENT
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.diffEndpoints
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

/** `diff_runs` (SDD 10): `endpoint_stats`' grouping of two runs, compared per endpoint by `diffEndpoints`. */
internal object DiffRuns {
    const val NAME = "diff_runs"
    private const val MAX_LIMIT = 500L
    private const val DEFAULT_LIMIT = 50L

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
        val known = storage.runs().map { it.id }.toSet()
        listOf(baseRunId, runId).firstOrNull { it !in known }?.let { throw ToolError("no run with id $it") }

        val diff =
            diffEndpoints(
                EndpointStats.group(storage, filter.copy(runId = baseRunId), maxExchanges),
                EndpointStats.group(storage, filter, maxExchanges),
            )
        return buildJsonObject {
            put("base_run_id", baseRunId)
            put("run_id", runId)
            put("added", JsonArray(diff.added.take(limit).map { it.toJson() }))
            put("added_count", diff.added.size)
            put("gone", JsonArray(diff.gone.take(limit).map { it.toJson() }))
            put("gone_count", diff.gone.size)
            putJsonArray("changed") { diff.changed.take(limit).forEach { add(it.toJson()) } }
            put("changed_count", diff.changed.size)
            put("unchanged", diff.unchanged)
            put("truncated", diff.truncated)
        }
    }

    private fun EndpointChange.toJson(): JsonObject =
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
            val (baseP50, runP50) = base.durationStats?.p50 to run.durationStats?.p50
            val (baseP95, runP95) = base.durationStats?.p95 to run.durationStats?.p95
            compare("p50_ms", baseP50.json(), runP50.json(), delta(baseP50, runP50))
            compare("p95_ms", baseP95.json(), runP95.json(), delta(baseP95, runP95))
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
