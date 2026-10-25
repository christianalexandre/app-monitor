package appmonitor.desktop.mcp

import appmonitor.v1.Header
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The body's bytes to return: UTF-8 text, else the uncut slice as base64 (SDD 10, ADR-0089 C.1). */
internal class BodySlice(
    val offset: Int,
    val bytes: ByteString,
    val text: String?,
)

/** `[offset, offset + max)` of [stored], its end moved back up to 3 bytes to a UTF-8 boundary when that makes it text. */
internal fun slice(
    stored: ByteString,
    offset: Long,
    max: Long,
): BodySlice {
    val start = minOf(offset, stored.size.toLong()).toInt()
    val end = minOf(start + max, stored.size.toLong()).toInt()
    var cut = end
    if (end < stored.size) {
        while (cut > start && end - cut < 3 && stored[cut].toInt() and 0xC0 == 0x80) cut--
        if (cut == start) cut = end
    }
    val text = stored.substring(start, cut).utf8OrNull()
    return if (text != null) BodySlice(start, stored.substring(start, cut), text) else BodySlice(start, stored.substring(start, end), null)
}

private fun ByteString.utf8OrNull(): String? =
    try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(toByteArray()))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

/** A body or frame payload as SDD 10's body object; null when nothing is stored and the device cut nothing. */
internal fun bodyJson(
    stored: ByteString,
    originalSize: Long?,
    truncatedByDevice: Boolean,
    contentType: String?,
    offset: Long,
    max: Long,
): JsonElement {
    if (stored.size == 0 && !truncatedByDevice) return JsonNull
    val slice = slice(stored, offset, max)
    return buildJsonObject {
        if (slice.text != null) {
            put("encoding", "utf8")
            put("text", slice.text)
        } else {
            put("encoding", "base64")
            put("base64", slice.bytes.base64())
        }
        put("offset", slice.offset)
        put("returned_bytes", slice.bytes.size)
        put("stored_bytes", stored.size)
        put("original_size", originalSize.json())
        put("truncated_by_device", truncatedByDevice)
        put("truncated_by_tool", slice.offset + slice.bytes.size < stored.size)
        put("content_type", contentType.json())
    }
}

internal fun List<Header>.contentType(): String? = firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value_
