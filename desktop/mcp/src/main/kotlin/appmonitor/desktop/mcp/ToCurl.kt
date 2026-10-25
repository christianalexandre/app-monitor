package appmonitor.desktop.mcp

import appmonitor.desktop.storage.Storage
import appmonitor.v1.Header
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.ByteString

/** `to_curl` (SDD 10): one stored request as a POSIX shell cURL command. */
internal object ToCurl {
    const val NAME = "to_curl"

    val description =
        "Returns one stored request, by the id search_requests gives, as a POSIX shell cURL command: method, URL, " +
            "every header as stored but Content-Length and Transfer-Encoding, which curl sets from the body (one zero Content-Length " +
            "is kept when no body is sent), and the body " +
            "(a binary one piped through base64 -d; none for HEAD). $DATA_NOTE"

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
        return command(storage, id)
    }

    /** Exchange [id]'s request as the command, after a `#` line when its body is left out or was cut by the device. */
    suspend fun command(
        storage: Storage,
        id: Long,
    ): String {
        val detail = storage.exchange(id)
        if (detail == null) throw ToolError("no exchange with id $id")
        val request = detail.request ?: throw ToolError("exchange $id has no request stored")
        val head = request.method == "HEAD"
        // curl refuses --head beside a body, and -X HEAD waits for a response body.
        val stored = if (head) ByteString.EMPTY else request.body
        val body = slice(stored, 0, stored.size.toLong())
        // A shell argument cannot hold NUL, so such a body goes the binary way.
        val text = body.text?.takeUnless { '\u0000' in it }
        val lines = mutableListOf<String>()
        if (head && request.body.size > 0) {
            lines += "# body of ${request.body.size} bytes left out: curl sends none with --head"
        } else if (request.body_truncated) {
            lines += "# body truncated by the device: ${request.body.size} of ${request.body_size ?: "unknown"} bytes"
        }
        val command =
            buildList {
                val method = if (head) "--head" else "-X ${quote(request.method)}"
                add("curl --globoff $method ${quote(request.url)}")
                // curl drops a header given as `Name:`; `Name;` sends it empty.
                sent(request.headers, keepZeroLength = !head && stored.size == 0).forEach {
                    add("-H ${quote(if (it.value_.isEmpty()) "${it.name};" else "${it.name}: ${it.value_}")}")
                }
                when {
                    stored.size == 0 -> Unit
                    text != null -> add("--data-raw ${quote(text)}")
                    else -> add("--data-binary @-")
                }
            }.joinToString(" \\\n  ")
        lines += if (stored.size > 0 && text == null) "printf %s ${quote(stored.base64())} | base64 -d | $command" else command
        return lines.joinToString("\n")
    }

    /** A stored one would not match a body cut by the device; curl computes its own. */
    private val FRAMING = setOf("content-length", "transfer-encoding")

    /** [headers] but [FRAMING]; curl sends no Content-Length without a body, so the first zero one stays when [keepZeroLength]. */
    private fun sent(
        headers: List<Header>,
        keepZeroLength: Boolean,
    ): List<Header> {
        var keep = keepZeroLength
        return headers.filter { header ->
            val name = header.name.lowercase()
            val zero = keep && name == "content-length" && header.value_.trim().toLongOrNull() == 0L
            if (zero) keep = false
            name !in FRAMING || zero
        }
    }

    /** [value] in single quotes, each `'` written as `'\''`. */
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
