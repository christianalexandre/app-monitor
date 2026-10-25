package appmonitor.desktop.mcp

import appmonitor.desktop.storage.StorageHealth
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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

/** [text] as one [TextContent], after a `#` line per warning when [health] counts unsaved events (`to_curl`). */
internal fun textResult(
    text: String,
    health: StorageHealth,
) = CallToolResult(content = listOf(TextContent(listOfNotNull(warning(health)?.let { "# warning: $it" }, text).joinToString("\n"))))

private fun warning(health: StorageHealth): String? =
    if (health.notSaved == 0L) null else "${health.notSaved} events were not saved" + (health.lastError?.let { ": $it" } ?: "")

/** [result] as the text of one [TextContent], with a `warnings` field when [health] counts unsaved events. */
internal fun jsonResult(
    result: JsonObject,
    health: StorageHealth,
): CallToolResult {
    val body = warning(health)?.let { JsonObject(result + ("warnings" to JsonArray(listOf(JsonPrimitive(it))))) } ?: result
    return CallToolResult(content = listOf(TextContent(body.toString())))
}

/** [har] as [jsonResult], the warning in `log._warnings`: HAR 1.2 allows no other custom field. */
internal fun harResult(
    har: JsonObject,
    health: StorageHealth,
): CallToolResult {
    val log = har.getValue("log") as JsonObject
    val body =
        warning(health)?.let { JsonObject(mapOf("log" to JsonObject(log + ("_warnings" to JsonArray(listOf(JsonPrimitive(it))))))) } ?: har
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

/** An optional string argument, [default] when absent or null. */
internal fun JsonObject?.string(
    name: String,
    default: String? = null,
): String? {
    val value = this?.get(name)
    if (value == null || value is JsonNull) return default
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ToolError("$name must be a string")
}

/** An optional boolean argument, [default] when absent or null. */
internal fun JsonObject?.boolean(
    name: String,
    default: Boolean,
): Boolean {
    val value = this?.get(name)
    if (value == null || value is JsonNull) return default
    return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw ToolError("$name must be true or false")
}

/** An optional argument that is one of [choices], [default] when absent or null. */
internal fun JsonObject?.choice(
    name: String,
    choices: Collection<String>,
    default: String?,
): String? {
    val value = string(name, default) ?: return null
    if (value !in choices) throw ToolError("$name must be one of ${choices.joinToString()}")
    return value
}
