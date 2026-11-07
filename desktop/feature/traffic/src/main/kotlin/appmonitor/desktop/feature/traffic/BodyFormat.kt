package appmonitor.desktop.feature.traffic

import appmonitor.desktop.designsystem.SyntaxKind
import appmonitor.desktop.designsystem.SyntaxSpan
import appmonitor.desktop.designsystem.matchesOf
import appmonitor.desktop.ui.utf8OrNull
import appmonitor.v1.Header
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString

internal enum class BodyFormat(
    val label: String,
) {
    Text("Text"),
    Json("JSON"),
    Hex("Hex"),
}

private val JSON_LITERAL = Regex("true|false|null|-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")

/** Indented JSON, or null when [text] is not a JSON document. */
private fun prettyJsonOrNull(text: String): PrettyJson? =
    try {
        if (Json.parseToJsonElement(text).isStrictJson()) indentJson(text) else null
    } catch (_: SerializationException) {
        null
    }

/** Indented JSON as the view shows it, and the string values it [cut] past [MAX_SHOWN_VALUE_CHARS]. */
internal class PrettyJson(
    val text: String,
    val cut: List<CutValue>,
)

/** A string value's text as sent, between its quotes, of which the view shows the first [shown] characters. */
internal class CutValue(
    val value: String,
    val shown: Int,
)

/** Characters of a string value the JSON view shows before `… (N more)` (ADR-0208); Text, Hex, Copy and cURL keep it whole. */
internal const val MAX_SHOWN_VALUE_CHARS = 200

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
private fun indentJson(text: String): PrettyJson {
    val out = StringBuilder()
    val cut = ArrayList<CutValue>()
    var depth = 0
    var index = 0

    fun newline() = out.append('\n').append("    ".repeat(depth))
    while (index < text.length) {
        if (out.length > MAX_RENDERED_CHARS) return PrettyJson(out.append("\n… stopped at $MAX_RENDERED_CHARS characters").toString(), cut)
        val c = text[index]
        when {
            c == '"' -> {
                val end = text.stringEnd(index)
                val value = text.substring(index + 1, end)
                val shown = value.shownLength()
                if (shown < value.length && text.nextSignificant(end + 1) != ':') {
                    out.append('"').append(value, 0, shown).append("… (${value.length - shown} more)\"")
                    cut += CutValue(value, shown)
                } else {
                    out.append(text, index, end + 1)
                }
                index = end
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
        index++
    }
    return PrettyJson(out.toString(), cut)
}

// The index of the quote closing the string opened at [open]; the document parsed, so there is one.
private fun String.stringEnd(open: Int): Int {
    var index = open + 1
    while (this[index] != '"') index += if (this[index] == '\\') 2 else 1
    return index
}

// Characters of a string value's raw text shown: never inside an escape or between a surrogate pair's halves.
private fun String.shownLength(): Int {
    if (length <= MAX_SHOWN_VALUE_CHARS) return length
    var index = 0
    while (index < MAX_SHOWN_VALUE_CHARS) {
        index +=
            when {
                this[index] == '\\' && this[index + 1] == 'u' -> 6
                this[index] == '\\' -> 2
                this[index].isHighSurrogate() -> 2
                else -> 1
            }
    }
    return minOf(index, length)
}

/** Most characters of indented JSON; deep nesting would otherwise grow the output with depth × lines. */
internal const val MAX_RENDERED_CHARS = 16 shl 20

private fun closing(open: Char) = if (open == '{') '}' else ']'

private fun String.nextSignificant(from: Int): Char? {
    for (index in from until length) if (!this[index].isWhitespace()) return this[index]
    return null
}

/** Classic hex dump: offset, 16 bytes in hex, printable ASCII. */
internal fun hexDump(body: ByteString): String {
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

/** A body opened (ADR-0199), with its UTF-8 text and indented JSON computed at most once, shared by [defaultFormat] and [render]. */
internal class DecodedBody(
    sent: ByteString,
    headers: List<Header> = emptyList(),
) {
    // Renderings of two formats may run at once off the UI thread.
    private val opened by lazy { openBody(headers, sent) }
    val bytes: ByteString get() = opened.bytes
    val note: String? get() = opened.note
    val text: String? by lazy { utf8OrNull(if (opened.partial) bytes.withoutCutCharacter() else bytes) }
    val prettyJson: PrettyJson? by lazy { text?.let(::prettyJsonOrNull) }
}

/** A body rendered as [format], split into [chunks] so the UI lays out only the visible ones. */
internal data class RenderedBody(
    val format: BodyFormat,
    val chunks: List<String>,
    val note: String? = null,
    val cut: List<CutValue> = emptyList(),
)

/** Renders [body] as [format], or as its [defaultFormat] when null; slow on large bodies, so off the UI thread. */
internal fun renderBody(
    body: DecodedBody,
    format: BodyFormat?,
): RenderedBody {
    val chosen = format ?: defaultFormat(body)
    val cut = if (chosen == BodyFormat.Json) body.prettyJson?.cut.orEmpty() else emptyList()
    return RenderedBody(chosen, chunked(render(body, chosen)), body.note, cut)
}

/**
 * [rendered] cut at a line break after [CHUNK_LINES] lines, or before a line that would pass [CHUNK_CHARS] characters;
 * inside a line only when that line alone passes them. A chunk holds at most [CHUNK_CHARS] characters, plus one surrogate.
 */
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
        } else if (index - start >= CHUNK_CHARS && lineStart > start) {
            chunks += rendered.substring(start, lineStart - 1)
            start = lineStart
            lines = 0
        } else if (index - start >= CHUNK_CHARS && !rendered[index].isLowSurrogate()) {
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

/** JSON whenever it parses as strict JSON, whatever the `Content-Type` (ADR-0208); text when it is UTF-8, hex otherwise. */
internal fun defaultFormat(body: DecodedBody): BodyFormat =
    when {
        body.prettyJson != null -> BodyFormat.Json
        body.text != null -> BodyFormat.Text
        else -> BodyFormat.Hex
    }

/** How many matches of [query] are in the parts of [cut] values the JSON view leaves out. */
internal fun hiddenMatches(
    cut: List<CutValue>,
    query: String,
): Int = cut.sumOf { value -> matchesOf(value.value, query).count { it.last >= value.shown } }

/** [body] rendered as [format]; formats that do not apply say so instead of failing. */
internal fun render(
    body: DecodedBody,
    format: BodyFormat,
): String =
    when (format) {
        BodyFormat.Text -> body.text ?: "Not valid UTF-8 text"
        BodyFormat.Json -> body.prettyJson?.text ?: "Not a JSON document"
        BodyFormat.Hex -> hexDump(body.bytes)
    }

/** Keys, strings and literals of a piece of indented JSON; a string never spans lines there, so any chunk of it works. */
internal fun jsonSyntax(chunk: String): List<SyntaxSpan> {
    val spans = ArrayList<SyntaxSpan>()
    var index = 0
    while (index < chunk.length) {
        val c = chunk[index]
        when {
            c == '"' -> {
                var end = index + 1
                while (end < chunk.length && chunk[end] != '"' && chunk[end] != '\n') end += if (chunk[end] == '\\') 2 else 1
                end = minOf(end + 1, chunk.length)
                val key = chunk.nextSignificant(end) == ':'
                spans += SyntaxSpan(index, end, if (key) SyntaxKind.Key else SyntaxKind.String)
                index = end
            }

            c == '-' || c.isLetterOrDigit() -> {
                var end = index + 1
                while (end < chunk.length && (chunk[end].isLetterOrDigit() || chunk[end] in "+-.")) end++
                if (chunk.substring(index, end).matches(JSON_LITERAL)) spans += SyntaxSpan(index, end, SyntaxKind.Value)
                index = end
            }

            else -> {
                index++
            }
        }
    }
    return spans
}
