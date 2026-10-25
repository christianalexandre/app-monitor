package appmonitor.desktop.mcp

import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.TrafficQueryRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The window's "Export HAR" and the detail's cURL tab (SDD 01): `export_har`'s and `to_curl`'s code (ADR-0098). */
object Exports {
    /** Exchanges one HAR holds, as `export_har`'s `max_entries` at most. */
    const val MAX_ENTRIES = ExportHar.MAX_ENTRIES

    private val pretty = Json { prettyPrint = true }

    /** The first [MAX_ENTRIES] exchanges [filter] matches, oldest first, bodies whole; refused past `export_har`'s 32 MiB. */
    suspend fun har(
        queries: TrafficQueryRepository,
        filter: ExchangeFilter,
        version: String = "dev",
    ): Har {
        val har = refusals { ExportHar.log(queries, filter, MAX_ENTRIES, ExportHar.FULL, version) }
        return Har(pretty.encodeToString(JsonObject.serializer(), har.json), har.entries, har.more, har.connections)
    }

    /** Exchange [id]'s request as `to_curl` writes it; refused when the exchange or its request is not stored. */
    suspend fun curl(
        queries: TrafficQueryRepository,
        id: Long,
    ): String = refusals { ToCurl.command(queries, id) }

    private inline fun <T> refusals(block: () -> T): T =
        try {
            block()
        } catch (e: ToolError) {
            throw ExportRefused(e.message!!)
        }
}

/** A HAR 1.2 file's [text], its [entries], whether more exchanges matched than it holds, and the [connections] left out. */
data class Har(
    val text: String,
    val entries: Int,
    val more: Boolean,
    val connections: Int,
)

/** An export the stored data cannot give, in one sentence (the MCP tool's error text). */
class ExportRefused(
    message: String,
) : Exception(message)
