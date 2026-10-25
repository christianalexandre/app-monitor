package appmonitor.desktop.mcp

import appmonitor.desktop.storage.StorageHealth
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Ends every tool's description (ADR-0089 A.1). */
internal const val DATA_NOTE =
    "Data is what the app sent: headers and bodies may hold tokens when the app turned masking off."

internal const val CLOSING = "App Monitor is closing"

/** A tool's answer to a bad argument, an unknown id or a closing store: one sentence (SDD 10). */
internal class ToolError(
    message: String,
) : Exception(message)

internal fun errorResult(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)

/** [result] as the text of one [TextContent], with a `warnings` field when [health] counts unsaved events. */
internal fun jsonResult(
    result: JsonObject,
    health: StorageHealth,
): CallToolResult {
    val body =
        if (health.notSaved == 0L) {
            result
        } else {
            val warning = "${health.notSaved} events were not saved" + (health.lastError?.let { ": $it" } ?: "")
            JsonObject(result + ("warnings" to JsonArray(listOf(JsonPrimitive(warning)))))
        }
    return CallToolResult(content = listOf(TextContent(body.toString())))
}

private val isoUtc = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** ISO 8601 UTC with milliseconds, null kept as JSON null. */
internal fun time(epochMs: Long?): JsonElement = epochMs?.let { JsonPrimitive(isoUtc.format(Instant.ofEpochMilli(it))) } ?: JsonNull

internal fun Long?.json(): JsonElement = this?.let { JsonPrimitive(it) } ?: JsonNull

internal fun String?.json(): JsonElement = this?.let { JsonPrimitive(it) } ?: JsonNull

/** An optional integer argument in [range], [default] when absent or null. */
internal fun JsonObject?.long(
    name: String,
    range: LongRange,
    default: Long?,
): Long? {
    val value = this?.get(name)
    if (value == null || value is JsonNull) return default
    val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()
    if (number == null || number !in range) throw ToolError("$name must be an integer from ${range.first} to ${range.last}")
    return number
}
