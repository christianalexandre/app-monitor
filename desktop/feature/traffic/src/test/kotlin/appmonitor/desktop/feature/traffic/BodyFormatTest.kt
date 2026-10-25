package appmonitor.desktop.feature.traffic

import appmonitor.desktop.ui.formatDuration
import appmonitor.protocol.Protocol
import appmonitor.v1.Header
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

class BodyFormatTest {
    private val json = Header("content-type", "application/json; charset=utf-8")
    private val binary = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte()).toByteString()

    @Test
    fun `pretty prints json`() {
        val body = """{"name":"café","tags":[1,2]}""".encodeUtf8()

        assertEquals(BodyFormat.Json, defaultFormat(listOf(json), decoded(body)))
        assertEquals("{\n    \"name\": \"café\",\n    \"tags\": [\n        1,\n        2\n    ]\n}", render(decoded(body), BodyFormat.Json))
    }

    @Test
    fun `falls back to text, then hex`() {
        assertEquals(BodyFormat.Text, defaultFormat(listOf(json), decoded("not json".encodeUtf8())))
        assertEquals(BodyFormat.Hex, defaultFormat(emptyList(), decoded(binary)))
        assertEquals("Not valid UTF-8 text", render(decoded(binary), BodyFormat.Text))
        assertEquals("Not a JSON document", render(decoded("plain".encodeUtf8()), BodyFormat.Json))
        assertEquals("-1.5e3", render(decoded(" -1.5e3 ".encodeUtf8()), BodyFormat.Json))
    }

    @Test
    fun `keeps json values exactly as sent`() {
        val body = """{"id":12345678901234567890,"x":1.0e3,"s":"a\\\"{,:}","e":{},"l":[ ]}""".encodeUtf8()

        assertEquals(
            "{\n    \"id\": 12345678901234567890,\n    \"x\": 1.0e3,\n    \"s\": \"a\\\\\\\"{,:}\",\n    \"e\": {},\n    \"l\": []\n}",
            render(decoded(body), BodyFormat.Json),
        )
    }

    @Test
    fun `rejects bare words at any depth`() {
        assertEquals("Not a JSON document", render(decoded("""{"a": foo}""".encodeUtf8()), BodyFormat.Json))
        assertEquals("Not a JSON document", render(decoded("""[1, [true, nope]]""".encodeUtf8()), BodyFormat.Json))
        assertEquals("Not a JSON document", render(decoded("""{"n": 01}""".encodeUtf8()), BodyFormat.Json))
        assertEquals(BodyFormat.Text, defaultFormat(listOf(json), decoded("""{"a": foo}""".encodeUtf8())))
        assertEquals("{\n    \"a\": null\n}", render(decoded("""{"a": null}""".encodeUtf8()), BodyFormat.Json))
    }

    @Test
    fun `formats a body at the protocol limit in linear time`() {
        val element = """{"k":[1,2,{"a":"v"}]}"""
        val count = Protocol.MAX_BODY_BYTES / (element.length + 1)
        val body = List(count) { element }.joinToString(",", "[", "]").encodeUtf8()

        val (rendered, elapsed) = measureTimedValue { render(decoded(body), BodyFormat.Json) }

        assertEquals(1 + count * 9 + 1, rendered.lines().size)
        assertTrue(elapsed < 5.seconds, "took $elapsed")
    }

    @Test
    fun `decodes and parses a body once for the default format and its rendering`() {
        val body = decoded("""{"a":1}""".encodeUtf8())

        assertEquals(BodyFormat.Json, defaultFormat(listOf(json), body))
        assertSame(body.prettyJson, render(body, BodyFormat.Json))
        assertSame(body.text, render(body, BodyFormat.Text))
    }

    @Test
    fun `dumps a multi-megabyte body as hex in linear time`() {
        val body = ByteArray(4_000_000) { it.toByte() }.toByteString()

        val (rendered, elapsed) = measureTimedValue { render(decoded(body), BodyFormat.Hex) }

        assertEquals(250_000, rendered.lines().size)
        assertEquals("003d08f0  f0 f1 f2 f3 f4 f5 f6 f7 f8 f9 fa fb fc fd fe ff  |................|", rendered.lines().last())
        assertTrue(elapsed < 5.seconds, "took $elapsed")
    }

    @Test
    fun `dumps hex with offsets and ascii`() {
        val body = "0123456789abcdefXY".encodeUtf8()

        assertEquals("00000000  00 7f 80 ff                                      |....|", render(decoded(binary), BodyFormat.Hex))
        assertEquals(
            "00000000  30 31 32 33 34 35 36 37 38 39 61 62 63 64 65 66  |0123456789abcdef|\n" +
                "00000010  58 59                                            |XY|",
            render(decoded(body), BodyFormat.Hex),
        )
    }

    @Test
    fun `chunks a rendering by lines and inside long lines`() {
        val lines = List(CHUNK_LINES * 2 + 1) { "line $it" }.joinToString("\n")
        val long = "x".repeat(CHUNK_CHARS * 2 + 1)

        assertEquals(listOf(""), chunked(""))
        assertEquals(lines, chunked(lines).joinToString("\n"))
        assertEquals(3, chunked(lines).size)
        assertEquals(listOf(CHUNK_CHARS, CHUNK_CHARS, 1), chunked(long).map { it.length })
        assertTrue(chunked("😀".repeat(CHUNK_CHARS)).none { it.first().isLowSurrogate() })
    }

    @Test
    fun `keeps short lines whole when a chunk passes the character bound`() {
        val lines = List(CHUNK_LINES) { "$it".padEnd(90, 'x') }

        val chunks = chunked(lines.joinToString("\n"))

        assertTrue(chunks.size > 1)
        assertEquals(lines, chunks.flatMap { it.split("\n") })
    }

    @Test
    fun `cuts before a long line after short ones, then inside it, each chunk within the character bound`() {
        val chunks = chunked("short\n" + "x".repeat(CHUNK_CHARS + 1))

        assertEquals(listOf("short", "x".repeat(CHUNK_CHARS), "x"), chunks)
    }

    @Test
    fun `no chunk passes the character bound`() {
        val rendered = List(40) { "y".repeat(it * 997 % 12_000) }.joinToString("\n")

        val chunks = chunked(rendered)

        assertEquals(rendered.replace("\n", ""), chunks.joinToString("").replace("\n", ""))
        assertTrue(chunks.all { it.length <= CHUNK_CHARS }, chunks.map { it.length }.toString())
    }

    @Test
    fun `deeply nested json stops at the rendering cap`() {
        val depth = 400
        val body = List(Protocol.MAX_BODY_BYTES / 2) { "1" }.joinToString(",", "[".repeat(depth), "]".repeat(depth)).encodeUtf8()

        val (rendered, elapsed) = measureTimedValue { renderBody(listOf(json), decoded(body), null) }

        assertEquals(BodyFormat.Json, rendered.format)
        val text = rendered.chunks.joinToString("\n")
        assertTrue(text.length < MAX_RENDERED_CHARS + 10_000, "rendered ${text.length} characters")
        assertTrue(text.endsWith("… stopped at $MAX_RENDERED_CHARS characters"))
        assertTrue(elapsed < 10.seconds, "took $elapsed")
    }

    @Test
    fun `formats durations`() {
        assertEquals("250 ms", formatDuration(250))
        assertEquals("1.50 s", formatDuration(1_500))
    }

    private fun decoded(body: ByteString) = DecodedBody(body)
}
