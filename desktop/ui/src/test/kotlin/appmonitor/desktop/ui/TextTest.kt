package appmonitor.desktop.ui

import appmonitor.v1.Header
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TextTest {
    @Test
    fun `headers read back what they are written as`() {
        val headers = listOf(Header("Accept", "*/*"), Header("X-Time", "12:30"))
        assertEquals(headers, headersOf(headersText(headers) + "\n\n"))
        assertFailsWith<IllegalArgumentException> { headersOf(": no name") }
    }

    @Test
    fun `durations under a second stay in ms`() {
        assertEquals("999 ms", formatDuration(999))
        assertEquals("1.50 s", formatDuration(1_500))
    }

    @Test
    fun `only valid UTF-8 reads as text`() {
        assertEquals("olá", utf8OrNull("olá".encodeUtf8()))
        assertNull(utf8OrNull(byteArrayOf(0xc3.toByte(), 0x28).toByteString()))
    }
}
