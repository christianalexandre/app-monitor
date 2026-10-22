package appmonitor.desktop.app

import appmonitor.desktop.app.ui.BodyFormat
import appmonitor.desktop.app.ui.defaultFormat
import appmonitor.desktop.app.ui.formatDuration
import appmonitor.desktop.app.ui.render
import appmonitor.v1.Header
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals

class BodyFormatTest {
    private val json = Header("content-type", "application/json; charset=utf-8")
    private val binary = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte()).toByteString()

    @Test
    fun `pretty prints json`() {
        val body = """{"name":"café","tags":[1,2]}""".encodeUtf8()

        assertEquals(BodyFormat.Json, defaultFormat(listOf(json), body))
        assertEquals("{\n    \"name\": \"café\",\n    \"tags\": [\n        1,\n        2\n    ]\n}", render(body, BodyFormat.Json))
    }

    @Test
    fun `falls back to text, then hex`() {
        assertEquals(BodyFormat.Text, defaultFormat(listOf(json), "not json".encodeUtf8()))
        assertEquals(BodyFormat.Hex, defaultFormat(emptyList(), binary))
        assertEquals("Not valid UTF-8 text", render(binary, BodyFormat.Text))
        assertEquals("Not a JSON document", render("plain".encodeUtf8(), BodyFormat.Json))
        assertEquals("-1.5e3", render(" -1.5e3 ".encodeUtf8(), BodyFormat.Json))
    }

    @Test
    fun `keeps json values exactly as sent`() {
        val body = """{"id":12345678901234567890,"x":1.0e3,"s":"a\\\"{,:}","e":{},"l":[ ]}""".encodeUtf8()

        assertEquals(
            "{\n    \"id\": 12345678901234567890,\n    \"x\": 1.0e3,\n    \"s\": \"a\\\\\\\"{,:}\",\n    \"e\": {},\n    \"l\": []\n}",
            render(body, BodyFormat.Json),
        )
    }

    @Test
    fun `dumps hex with offsets and ascii`() {
        val body = "0123456789abcdefXY".encodeUtf8()

        assertEquals("00000000  00 7f 80 ff                                      |....|", render(binary, BodyFormat.Hex))
        assertEquals(
            "00000000  30 31 32 33 34 35 36 37 38 39 61 62 63 64 65 66  |0123456789abcdef|\n" +
                "00000010  58 59                                            |XY|",
            render(body, BodyFormat.Hex),
        )
    }

    @Test
    fun `formats durations`() {
        assertEquals("250 ms", formatDuration(250))
        assertEquals("1.50 s", formatDuration(1_500))
    }
}
