package appmonitor.desktop.mcp

import appmonitor.desktop.storage.RunSummary
import appmonitor.desktop.storage.SessionSummary
import appmonitor.desktop.storage.Storage
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** `list_runs` (SDD 10): runs newest first, with their sessions and exchange counts. */
internal object ListRuns {
    const val NAME = "list_runs"
    private const val MAX_LIMIT = 100L
    private const val DEFAULT_LIMIT = 20L

    val description =
        "Lists the recorded runs, newest first: each run's time window, whether it is the current one, its exchange " +
            "count and the app sessions (device, app, SDK) that joined it. Page with next_before_id. $DATA_NOTE"

    val inputSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", MAX_LIMIT)
                        put("default", DEFAULT_LIMIT)
                        put("description", "Runs per page.")
                    }
                    putJsonObject("before_id") {
                        put("type", "integer")
                        put("description", "The next_before_id of the previous page.")
                    }
                },
        )

    suspend fun call(
        storage: Storage,
        arguments: JsonObject?,
    ): JsonObject {
        val limit = arguments.long("limit", 1..MAX_LIMIT, DEFAULT_LIMIT)!!
        val beforeId = arguments.long("before_id", 1..Long.MAX_VALUE, null) ?: Long.MAX_VALUE
        val runs = storage.runSummaries(beforeId, limit)
        val current = storage.currentRun.value
        return buildJsonObject {
            put("runs", JsonArray(runs.map { it.toJson(current) }))
            put("next_before_id", (if (runs.size.toLong() == limit) runs.last().run.id else null).json())
        }
    }

    private fun RunSummary.toJson(current: Long) =
        buildJsonObject {
            put("id", run.id)
            put("name", run.name.json())
            put("started_at", time(run.startedAtMs))
            put("ended_at", time(run.endedAtMs))
            put("current", run.id == current)
            put("exchange_count", exchangeCount)
            put("sessions", JsonArray(sessions.map { it.toJson() }))
        }

    private fun SessionSummary.toJson() =
        buildJsonObject {
            put("session_id", session.id)
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("platform", platform.wireName("PLATFORM_"))
            put("os_version", osVersion)
            put("app_id", appId)
            put("app_version", appVersion)
            put("sdk_name", sdkName)
            put("connected_at", time(session.connectedAtMs))
            put("disconnected_at", time(session.disconnectedAtMs))
        }
}
