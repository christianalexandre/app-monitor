package appmonitor.desktop.ui

import appmonitor.desktop.domain.ConnectionBytes
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.HttpExchange
import appmonitor.v1.CaptureLayer
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

    @Test
    fun `a row and its detail show the same status`() {
        fun row(
            layer: CaptureLayer = CaptureLayer.CAPTURE_LAYER_ADAPTER,
            error: String? = null,
            endedAtMs: Long? = null,
            statusCode: Int? = null,
            grpcStatus: Int? = null,
        ) = Exchange(1, 1, "r", "GET", "https://a", "a", statusCode, error != null, 0, endedAtMs, layer, error, 0, grpcStatus)

        fun detail(row: Exchange) =
            HttpExchange(
                id = 1,
                requestId = "r",
                statusCode = row.statusCode,
                grpcStatus = row.grpcStatus,
                error = row.error,
                endedAtMs = row.endedAtMs,
                captureLayer = row.captureLayer,
                connection = if (row.isConnection) ConnectionBytes(null, null) else null,
            )
        val rows =
            mapOf(
                "failed" to row(error = "timeout", statusCode = 200),
                "open" to row(layer = CaptureLayer.CAPTURE_LAYER_CONNECTION),
                "closed" to row(layer = CaptureLayer.CAPTURE_LAYER_CONNECTION, endedAtMs = 5),
                "grpc 14" to row(statusCode = 200, grpcStatus = 14),
                "404" to row(statusCode = 404),
                "…" to row(),
            )
        for ((status, exchange) in rows) {
            assertEquals(status, statusOf(exchange))
            assertEquals(status, statusOf(detail(exchange)))
        }
    }
}
