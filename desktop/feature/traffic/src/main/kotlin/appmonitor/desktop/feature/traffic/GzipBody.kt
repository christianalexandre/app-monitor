package appmonitor.desktop.feature.traffic

import appmonitor.v1.Header
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.zip.GZIPInputStream

/** Most bytes a gzip body opens to; 1 MiB of gzip can hold about 1 GiB (ADR-0199). */
internal const val MAX_OPENED_BYTES = 16 shl 20

// Small: a broken stream loses what the failing read had inflated.
private const val READ_BYTES = 1_024

/** What the window shows of a body; [note] says when that is not what was sent, [partial] when its end is missing. */
internal class OpenedBody(
    val bytes: ByteString,
    val note: String? = null,
    val partial: Boolean = false,
)

/** [sent] opened when `Content-Encoding` says gzip and it starts as gzip; some SDKs keep the header over decoded bytes (ADR-0199). */
internal fun openBody(
    headers: List<Header>,
    sent: ByteString,
): OpenedBody {
    if (!saysGzip(headers) || sent.size < 2 || sent[0] != 0x1f.toByte() || sent[1] != 0x8b.toByte()) return OpenedBody(sent)
    val out = ByteArrayOutputStream()
    val stop =
        try {
            inflate(sent, out)
        } catch (_: EOFException) {
            "cut"
        } catch (_: IOException) {
            "broken"
        }
    val read = out.size().coerceAtMost(MAX_OPENED_BYTES)
    return when {
        stop == null -> {
            OpenedBody(out.toByteArray().toByteString(), "Opened from gzip: ${sent.size} bytes sent, $read shown")
        }

        stop == OVER_THE_BOUND -> {
            val note = "Opened from gzip: the first $read bytes shown, it opens to more; ${sent.size} bytes sent"
            OpenedBody(out.toByteArray().toByteString(0, read), note, partial = true)
        }

        read == 0 -> {
            OpenedBody(sent, "Gzip stream $stop, nothing could be read: the ${sent.size} bytes as sent")
        }

        else -> {
            val note = "Gzip stream $stop: the $read bytes that could be read, from ${sent.size} sent"
            OpenedBody(out.toByteArray().toByteString(), note, partial = true)
        }
    }
}

private const val OVER_THE_BOUND = "over"

// One coding only: with several, the outermost could be anything else (ADR-0199).
private fun saysGzip(headers: List<Header>): Boolean =
    headers
        .firstOrNull { it.name.equals("Content-Encoding", ignoreCase = true) }
        ?.value_
        ?.trim()
        ?.lowercase() in GZIP_NAMES

private val GZIP_NAMES = setOf("gzip", "x-gzip")

/** Fills [out] up to one byte past the bound; null at the stream's end, [OVER_THE_BOUND] past the bound. */
private fun inflate(
    sent: ByteString,
    out: ByteArrayOutputStream,
): String? =
    GZIPInputStream(sent.toByteArray().inputStream()).use { input ->
        val buffer = ByteArray(READ_BYTES)
        while (out.size() <= MAX_OPENED_BYTES) {
            val read = input.read(buffer)
            if (read < 0) return@use null
            out.write(buffer, 0, read)
        }
        OVER_THE_BOUND
    }

/** [this] without a last UTF-8 character the cut left incomplete, which would make the whole of it not text. */
internal fun ByteString.withoutCutCharacter(): ByteString {
    for (back in 1..minOf(3, size)) {
        val byte = this[size - back].toInt() and 0xFF
        if (byte and 0xC0 == 0x80) continue
        val length =
            when {
                byte >= 0xF0 -> 4
                byte >= 0xE0 -> 3
                byte >= 0xC0 -> 2
                else -> 1
            }
        return if (length > back) substring(0, size - back) else this
    }
    return this
}
