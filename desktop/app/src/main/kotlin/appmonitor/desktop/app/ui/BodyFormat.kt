package appmonitor.desktop.app.ui

import appmonitor.v1.Header
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

private val JSON_LITERAL = Regex("true|false|null|-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")

/** UTF-8 text of [body], or null when it is not valid UTF-8. */
private fun utf8OrNull(body: ByteString): String? =
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

/** Indented JSON, or null when [text] is not a JSON document. */
private fun prettyJsonOrNull(text: String): String? =
    try {
        if (Json.parseToJsonElement(text).isStrictJson()) indentJson(text) else null
    } catch (_: SerializationException) {
        null
    }

// The parser accepts bare words as primitives at any depth; JSON only has quoted strings and these literals.
private fun JsonElement.isStrictJson(): Boolean {
    val pending = ArrayDeque(listOf(this))
    while (pending.isNotEmpty()) {
        when (val element = pending.removeLast()) {
            is JsonObject -> pending.addAll(element.values)
            is JsonArray -> pending.addAll(element)
            is JsonPrimitive -> if (!element.isString && !element.content.matches(JSON_LITERAL)) return false
        }
    }
    return true
}

// Re-indents without re-encoding: numbers and escapes keep the device's exact text.
private fun indentJson(text: String): String {
    val out = StringBuilder()
    var depth = 0
    var inString = false
    var escaped = false

    fun newline() = out.append('\n').append("    ".repeat(depth))
    for ((index, c) in text.withIndex()) {
        if (out.length > MAX_RENDERED_CHARS) return out.append("\n… stopped at $MAX_RENDERED_CHARS characters").toString()
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

/** Most characters of indented JSON; deep nesting would otherwise grow the output with depth × lines. */
internal const val MAX_RENDERED_CHARS = 16 shl 20

private fun closing(open: Char) = if (open == '{') '}' else ']'

private fun String.nextSignificant(from: Int): Char? {
    for (index in from until length) if (!this[index].isWhitespace()) return this[index]
    return null
}

/** Classic hex dump: offset, 16 bytes in hex, printable ASCII. */
private fun hexDump(body: ByteString): String {
    val bytes = body.toByteArray()
    val out = StringBuilder(bytes.size / 16 * ROW_LENGTH + ROW_LENGTH)
    for (offset in bytes.indices step 16) {
        if (offset > 0) out.append('\n')
        out.appendHex(offset.toLong(), 8).append("  ")
        val end = minOf(offset + 16, bytes.size)
        for (i in offset until offset + 16) {
            if (i > offset) out.append(' ')
            if (i < end) out.appendHex(bytes[i].toLong() and 0xFF, 2) else out.append("  ")
        }
        out.append("  |")
        for (i in offset until end) out.append(if (bytes[i] in 0x20..0x7E) bytes[i].toInt().toChar() else '.')
        out.append('|')
    }
    return out.toString()
}

private const val ROW_LENGTH = 8 + 2 + 16 * 3 - 1 + 2 + 16 + 2 + 1
private const val HEX_DIGITS = "0123456789abcdef"

private fun StringBuilder.appendHex(
    value: Long,
    digits: Int,
): StringBuilder {
    for (shift in (digits - 1) * 4 downTo 0 step 4) append(HEX_DIGITS[((value shr shift) and 0xF).toInt()])
    return this
}

/** A body with its UTF-8 text and indented JSON computed at most once, shared by [defaultFormat] and [render]. */
internal class DecodedBody(
    val bytes: ByteString,
) {
    // Renderings of two formats may run at once off the UI thread.
    val text: String? by lazy { utf8OrNull(bytes) }
    val prettyJson: String? by lazy { text?.let(::prettyJsonOrNull) }
}

/** A body rendered as [format], split into [chunks] so the UI lays out only the visible ones. */
internal data class RenderedBody(
    val format: BodyFormat,
    val chunks: List<String>,
)

/** Renders [body] as [format], or as its [defaultFormat] when null; slow on large bodies, so off the UI thread. */
internal fun renderBody(
    headers: List<Header>,
    body: DecodedBody,
    format: BodyFormat?,
): RenderedBody {
    val chosen = format ?: defaultFormat(headers, body)
    return RenderedBody(chosen, chunked(render(body, chosen)))
}

/** [rendered] cut at a line break after [CHUNK_LINES] lines or [CHUNK_CHARS] characters, inside a line only past [CHUNK_CHARS]. */
internal fun chunked(rendered: String): List<String> {
    val chunks = ArrayList<String>()
    var start = 0
    var lineStart = 0
    var lines = 0
    for (index in rendered.indices) {
        if (rendered[index] == '\n') {
            lineStart = index + 1
            if (++lines < CHUNK_LINES && index - start < CHUNK_CHARS) continue
            chunks += rendered.substring(start, index)
            start = index + 1
            lines = 0
        } else if (index - lineStart >= CHUNK_CHARS && !rendered[index].isLowSurrogate()) {
            chunks += rendered.substring(start, index)
            start = index
            lineStart = index
            lines = 0
        }
    }
    chunks += rendered.substring(start)
    return chunks
}

internal const val CHUNK_LINES = 100
internal const val CHUNK_CHARS = 8_192

/** JSON when the content type says so and it parses, text when it is UTF-8, hex otherwise. */
internal fun defaultFormat(
    headers: List<Header>,
    body: DecodedBody,
): BodyFormat {
    val contentType = headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value_.orEmpty()
    return when {
        "json" in contentType.lowercase() && body.prettyJson != null -> BodyFormat.Json
        body.text != null -> BodyFormat.Text
        else -> BodyFormat.Hex
    }
}

/** [body] rendered as [format]; formats that do not apply say so instead of failing. */
internal fun render(
    body: DecodedBody,
    format: BodyFormat,
): String =
    when (format) {
        BodyFormat.Text -> body.text ?: "Not valid UTF-8 text"
        BodyFormat.Json -> body.prettyJson ?: "Not a JSON document"
        BodyFormat.Hex -> hexDump(body.bytes)
    }

internal fun formatDuration(ms: Long): String = if (ms < 1_000) "$ms ms" else String.format(Locale.ROOT, "%.2f s", ms / 1_000.0)
