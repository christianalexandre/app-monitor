package appmonitor.desktop.mcp

import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.storage.StatusClass
import appmonitor.desktop.storage.Storage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The window's "Export HAR" and "Copy as cURL" (SDD 01): `export_har`'s and `to_curl`'s code (ADR-0098). */
object Exports {
    /** Exchanges one HAR holds, as `export_har`'s `max_entries` at most. */
    const val MAX_ENTRIES = ExportHar.MAX_ENTRIES

    private val pretty = Json { prettyPrint = true }

    /** The first [MAX_ENTRIES] exchanges [filter] matches, oldest first, bodies whole; refused past `export_har`'s 32 MiB. */
    suspend fun har(
        storage: Storage,
        filter: ExchangeFilter,
        version: String = "dev",
    ): Har {
        val har = refusals { ExportHar.log(storage, filter.toStored(), MAX_ENTRIES, ExportHar.FULL, version) }
        return Har(pretty.encodeToString(JsonObject.serializer(), har.json), har.entries, har.more, har.connections)
    }

    /** Exchange [id]'s request as `to_curl` writes it; refused when the exchange or its request is not stored. */
    suspend fun curl(
        storage: Storage,
        id: Long,
    ): String = refusals { ToCurl.command(storage, id) }

    private fun ExchangeFilter.toStored() =
        appmonitor.desktop.storage.ExchangeFilter(
            text = text,
            method = method,
            statusClass = statusClass?.let { StatusClass.valueOf(it.name) },
            failedOnly = failedOnly,
            deviceId = deviceId,
            appId = appId,
            runId = runId,
            sessionId = sessionId,
        )

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
