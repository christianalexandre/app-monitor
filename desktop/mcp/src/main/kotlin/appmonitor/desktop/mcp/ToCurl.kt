package appmonitor.desktop.mcp

import appmonitor.desktop.storage.Storage
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** `to_curl` (SDD 10): one stored request as a POSIX shell cURL command. */
internal object ToCurl {
    const val NAME = "to_curl"

    val description =
        "Returns one stored request, by the id search_requests gives, as a POSIX shell cURL command: method, URL, " +
            "every header as stored and the body (a binary one piped through base64 -d). $DATA_NOTE"

    val inputSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "The exchange's id.")
                    }
                },
            required = listOf("id"),
        )

    suspend fun call(
        storage: Storage,
        arguments: JsonObject?,
    ): String {
        val id = arguments.long("id", 1..Long.MAX_VALUE, null) ?: throw ToolError("id is required")
        val detail = storage.exchange(id)
        if (storage.isClosing) throw ToolError(CLOSING)
        if (detail == null) throw ToolError("no exchange with id $id")
        val request = detail.request ?: throw ToolError("exchange $id has no request stored")
        val body = slice(request.body, 0, request.body.size.toLong())
        // A shell argument cannot hold NUL, so such a body goes the binary way.
        val text = body.text?.takeUnless { '\u0000' in it }
        val lines = mutableListOf<String>()
        if (request.body_truncated) {
            lines += "# body truncated by the device: ${request.body.size} of ${request.body_size ?: "unknown"} bytes"
        }
        val command =
            buildList {
                val method = if (request.method == "HEAD") "--head" else "-X ${quote(request.method)}"
                add("curl --globoff $method ${quote(request.url)}")
                // curl drops a header given as `Name:`; `Name;` sends it empty.
                request.headers.forEach { add("-H ${quote(if (it.value_.isEmpty()) "${it.name};" else "${it.name}: ${it.value_}")}") }
                when {
                    request.body.size == 0 -> Unit
                    text != null -> add("--data-raw ${quote(text)}")
                    else -> add("--data-binary @-")
                }
            }.joinToString(" \\\n  ")
        lines += if (request.body.size > 0 && text == null) "printf %s ${quote(request.body.base64())} | base64 -d | $command" else command
        return lines.joinToString("\n")
    }

    /** [value] in single quotes, each `'` written as `'\''`. */
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
