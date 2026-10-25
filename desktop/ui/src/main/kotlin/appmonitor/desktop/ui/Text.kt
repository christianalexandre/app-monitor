package appmonitor.desktop.ui

import appmonitor.v1.Header
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** The methods a filter or a rule offers. */
val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

fun formatDuration(ms: Long): String = if (ms < 1_000) "$ms ms" else String.format(Locale.ROOT, "%.2f s", ms / 1_000.0)

/** UTF-8 text of [body], or null when it is not valid UTF-8. */
fun utf8OrNull(body: ByteString): String? =
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

/** One "Name: value" per line of [text], blank lines skipped; throws for a line without a name. */
fun headersOf(text: String): List<Header> =
    text.lines().filter { it.isNotBlank() }.map { line ->
        val colon = line.indexOf(':')
        require(colon > 0) { "A header line is \"Name: value\"" }
        Header(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
    }

/** [headers] as [headersOf] reads them back. */
fun headersText(headers: List<Header>): String = headers.joinToString("\n") { "${it.name}: ${it.value_}" }
