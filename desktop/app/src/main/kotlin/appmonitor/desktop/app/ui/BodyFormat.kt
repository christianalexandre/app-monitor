package appmonitor.desktop.app.ui

import appmonitor.v1.Header
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

internal enum class BodyFormat(
    val label: String,
) {
    Text("Text"),
    Json("JSON"),
    Hex("Hex"),
}

private val JSON_SCALAR = Regex("\"(?s).*\"|true|false|null|-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")

/** UTF-8 text of [body], or null when it is not valid UTF-8. */
internal fun utf8OrNull(body: ByteString): String? =
    try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body.toByteArray()))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

/** Indented JSON, or null when [body] is not a JSON document. */
internal fun prettyJsonOrNull(body: ByteString): String? {
    val text = utf8OrNull(body) ?: return null
    return try {
        val element = Json.parseToJsonElement(text)
        // The parser accepts a bare word as a primitive; JSON only has quoted strings and these literals.
        if (element is JsonPrimitive && !text.trim().matches(JSON_SCALAR)) return null
        indentJson(text)
    } catch (_: SerializationException) {
        null
    }
}

// Re-indents without re-encoding: numbers and escapes keep the device's exact text.
private fun indentJson(text: String): String {
    val out = StringBuilder()
    var depth = 0
    var inString = false
    var escaped = false

    fun newline() = out.append('\n').append("    ".repeat(depth))
    for ((index, c) in text.withIndex()) {
        when {
            inString -> {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            }

            c == '"' -> {
                inString = true
                out.append(c)
            }

            c == '{' || c == '[' -> {
                out.append(c)
                depth++
                if (text.nextSignificant(index + 1) != closing(c)) newline()
            }

            c == '}' || c == ']' -> {
                depth--
                if (out.last() != '{' && out.last() != '[') newline()
                out.append(c)
            }

            c == ',' -> {
                out.append(c)
                newline()
            }

            c == ':' -> {
                out.append(": ")
            }

            !c.isWhitespace() -> {
                out.append(c)
            }
        }
    }
    return out.toString()
}

private fun closing(open: Char) = if (open == '{') '}' else ']'

private fun String.nextSignificant(from: Int): Char? = substring(from).firstOrNull { !it.isWhitespace() }

/** Classic hex dump: offset, 16 bytes in hex, printable ASCII. */
internal fun hexDump(body: ByteString): String =
    body.toByteArray().toList().chunked(16).withIndex().joinToString("\n") { (row, bytes) ->
        val hex = bytes.joinToString(" ") { "%02x".format(it) }.padEnd(16 * 3 - 1)
        val ascii = bytes.joinToString("") { if (it in 0x20..0x7E) it.toInt().toChar().toString() else "." }
        "%08x  %s  |%s|".format(row * 16, hex, ascii)
    }

/** JSON when the content type says so and it parses, text when it is UTF-8, hex otherwise. */
internal fun defaultFormat(
    headers: List<Header>,
    body: ByteString,
): BodyFormat {
    val contentType = headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value_.orEmpty()
    return when {
        "json" in contentType.lowercase() && prettyJsonOrNull(body) != null -> BodyFormat.Json
        utf8OrNull(body) != null -> BodyFormat.Text
        else -> BodyFormat.Hex
    }
}

/** [body] rendered as [format]; formats that do not apply say so instead of failing. */
internal fun render(
    body: ByteString,
    format: BodyFormat,
): String =
    when (format) {
        BodyFormat.Text -> utf8OrNull(body) ?: "Not valid UTF-8 text"
        BodyFormat.Json -> prettyJsonOrNull(body) ?: "Not a JSON document"
        BodyFormat.Hex -> hexDump(body)
    }

internal fun formatDuration(ms: Long): String = if (ms < 1_000) "$ms ms" else String.format(Locale.ROOT, "%.2f s", ms / 1_000.0)
