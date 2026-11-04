package appmonitor.desktop.feature.traffic

import appmonitor.desktop.domain.CapturedBody
import appmonitor.desktop.domain.HttpExchange
import appmonitor.protocol.Protocol
import appmonitor.v1.Header
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

class GzipBodyTest {
    private val encoded = Header("content-encoding", "GZIP")
    private val json = Header("Content-Type", "application/json")

    @Test
    fun `a gzip body opens before the formats`() {
        val body = DecodedBody(gzip("""{"a":"café"}"""), listOf(encoded, json))

        assertEquals(BodyFormat.Json, defaultFormat(listOf(encoded, json), body))
        assertEquals("{\n    \"a\": \"café\"\n}", render(body, BodyFormat.Json))
        assertEquals("""{"a":"café"}""", render(body, BodyFormat.Text))
        assertTrue(render(body, BodyFormat.Hex).startsWith("00000000  7b 22 61"))
        assertEquals("Opened from gzip: ${gzip("""{"a":"café"}""").size} bytes sent, 13 shown", body.note)
    }

    @Test
    fun `x-gzip opens and several members read as one`() {
        val members = gzip("one ").toByteArray() + gzip("two").toByteArray()

        assertEquals("one two", DecodedBody(members.toByteString(), listOf(Header("Content-Encoding", "x-gzip"))).text)
    }

    @Test
    fun `a body under the gzip header that is not gzip stays as it came`() {
        val decoded = "already decoded".encodeUtf8()

        val body = DecodedBody(decoded, listOf(encoded))

        assertEquals(decoded, body.bytes)
        assertNull(body.note)
    }

    @Test
    fun `gzip bytes without the header, or under several codings, stay as they came`() {
        val bytes = gzip("text")

        listOf(emptyList(), listOf(Header("Content-Encoding", "br, gzip"))).forEach { headers ->
            assertSame(bytes, openBody(headers, bytes).bytes)
            assertNull(openBody(headers, bytes).note)
        }
    }

    @Test
    fun `a body over the bound stops at it and says so, fast`() {
        val bomb = gzip(ByteArray(MAX_OPENED_BYTES * 2))
        assertTrue(bomb.size <= Protocol.MAX_BODY_BYTES, "bomb is ${bomb.size} bytes")

        val (rendered, elapsed) = measureTimedValue { renderBody(listOf(encoded), DecodedBody(bomb, listOf(encoded)), null) }

        assertEquals(BodyFormat.Text, rendered.format)
        assertEquals(MAX_OPENED_BYTES, rendered.chunks.sumOf { it.length })
        assertEquals(
            "Opened from gzip: the first $MAX_OPENED_BYTES bytes shown, it opens to more; ${bomb.size} bytes sent",
            rendered.note,
        )
        assertTrue(elapsed < 5.seconds, "took $elapsed")
    }

    @Test
    fun `binary at the bound renders as hex in bounded time`() {
        val binary = gzip(Random(7).nextBytes(MAX_OPENED_BYTES))

        val (rendered, elapsed) = measureTimedValue { renderBody(listOf(encoded), DecodedBody(binary, listOf(encoded)), null) }

        assertEquals(BodyFormat.Hex, rendered.format)
        assertTrue(rendered.note!!.endsWith("$MAX_OPENED_BYTES shown"), rendered.note)
        assertTrue(elapsed < 10.seconds, "took $elapsed")
    }

    @Test
    fun `a cut stream shows what could be read and says so, a character cut in half left out of the text`() {
        val text = "é".repeat(20_000)
        val whole = gzip(text)
        val cut = whole.substring(0, whole.size - 12)

        val body = DecodedBody(cut, listOf(encoded))

        val read = body.bytes.size
        assertTrue(read in 1 until 40_000, "read $read")
        assertEquals("Gzip stream cut: the $read bytes that could be read, from ${cut.size} sent", body.note)
        assertTrue(text.startsWith(body.text!!), "not a prefix of the text")
        assertEquals(read / 2, body.text!!.length)
    }

    @Test
    fun `a cut JSON document opens as text`() {
        val whole = gzip("""{"items":[${List(5_000) { it }.joinToString(",")}]}""")

        val body = DecodedBody(whole.substring(0, whole.size / 2), listOf(encoded, json))

        assertEquals(BodyFormat.Text, defaultFormat(listOf(encoded, json), body))
        assertTrue(body.text!!.startsWith("""{"items":[0,1,2"""))
    }

    @Test
    fun `only the trailer missing still shows the whole body, as cut`() {
        val whole = gzip("whole")

        val body = DecodedBody(whole.substring(0, whole.size - 4), listOf(encoded))

        assertEquals("whole", body.text)
        assertEquals("Gzip stream cut: the 5 bytes that could be read, from ${whole.size - 4} sent", body.note)
    }

    @Test
    fun `a checksum that does not match shows the body and says it is broken`() {
        val text = Random(3).nextBytes(64_000)
        val whole = gzip(text).toByteArray()
        whole[whole.size - 2] = (whole[whole.size - 2] + 1).toByte()

        val body = DecodedBody(whole.toByteString(), listOf(encoded))

        assertEquals(text.size, body.bytes.size)
        assertEquals("Gzip stream broken: the ${text.size} bytes that could be read, from ${whole.size} sent", body.note)
    }

    @Test
    fun `a stream broken in the middle shows what came before it`() {
        val text = List(40_000) { "line $it" }.joinToString("\n")
        val whole = gzip(text).toByteArray()
        whole.fill(0xFF.toByte(), whole.size / 2, whole.size / 2 + 64)

        val body = DecodedBody(whole.toByteString(), listOf(encoded))

        assertTrue(body.note!!.startsWith("Gzip stream broken: the "), body.note)
        assertTrue(body.bytes.size > 1_000, "read ${body.bytes.size}")
        assertTrue(
            body.bytes
                .toByteArray()
                .take(1_000)
                .toByteArray()
                .contentEquals(text.toByteArray().copyOf(1_000)),
        )
    }

    @Test
    fun `a stream nothing can be read from shows the bytes as sent and says so`() {
        val header = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0).toByteString()
        val method = byteArrayOf(0x1f, 0x8b.toByte(), 7, 0, 0, 0, 0, 0, 0, 0, 1).toByteString()

        assertEquals(header, DecodedBody(header, listOf(encoded)).bytes)
        assertEquals("Gzip stream cut, nothing could be read: the 4 bytes as sent", DecodedBody(header, listOf(encoded)).note)
        assertEquals(method, DecodedBody(method, listOf(encoded)).bytes)
        assertEquals("Gzip stream broken, nothing could be read: the 11 bytes as sent", DecodedBody(method, listOf(encoded)).note)
    }

    @Test
    fun `the copied body is the opened one`() {
        val exchange =
            HttpExchange(
                id = 1,
                requestId = "r1",
                requestHeaders = listOf(encoded),
                requestBody = CapturedBody(gzip("sent gzip")),
                responseHeaders = listOf(Header("Content-Encoding", "gzip")),
                responseBody = CapturedBody(gzip(byteArrayOf(0x00, 0xFF.toByte()))),
            )

        assertEquals("sent gzip", textOf(exchange, DetailPart.RequestBody))
        assertTrue(textOf(exchange, DetailPart.ResponseBody)!!.startsWith("00000000  00 ff "))
    }

    @Test
    fun `a gzip body that opens to nothing has nothing to copy`() {
        val exchange =
            HttpExchange(
                id = 1,
                requestId = "r1",
                requestHeaders = listOf(encoded),
                requestBody = CapturedBody(gzip("")),
                responseHeaders = listOf(encoded),
                responseBody = CapturedBody(gzip("")),
            )

        assertNull(textOf(exchange, DetailPart.RequestBody))
        assertNull(textOf(exchange, DetailPart.ResponseBody))
    }

    @Test
    fun `a character cut in half leaves, a whole one stays`() {
        listOf("é", "€", "😀").forEach { character ->
            val bytes = ("a$character").encodeUtf8()
            assertEquals(bytes, bytes.withoutCutCharacter())
            assertEquals("a".encodeUtf8(), bytes.substring(0, bytes.size - 1).withoutCutCharacter())
        }
        assertEquals("a".encodeUtf8(), "a".encodeUtf8().withoutCutCharacter())
    }

    private fun gzip(text: String): ByteString = gzip(text.toByteArray())

    private fun gzip(bytes: ByteArray): ByteString {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray().toByteString()
    }
}
