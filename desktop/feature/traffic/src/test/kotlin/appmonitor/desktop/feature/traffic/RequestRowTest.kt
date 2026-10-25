package appmonitor.desktop.feature.traffic

import appmonitor.desktop.designsystem.BadgeTone
import appmonitor.desktop.domain.Exchange
import appmonitor.v1.CaptureLayer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestRowTest {
    @Test
    fun `a status badge takes the tone of its class`() {
        assertEquals(BadgeTone.Neutral, toneOf(row()))
        assertEquals(BadgeTone.Neutral, toneOf(row(statusCode = 101)))
        assertEquals(BadgeTone.Success, toneOf(row(statusCode = 204)))
        assertEquals(BadgeTone.Info, toneOf(row(statusCode = 304)))
        assertEquals(BadgeTone.Warning, toneOf(row(statusCode = 404)))
        assertEquals(BadgeTone.Error, toneOf(row(statusCode = 503)))
        assertEquals(BadgeTone.Error, toneOf(row(failed = true)))
        assertEquals(BadgeTone.Error, toneOf(row(statusCode = 200, grpcStatus = 14)))
        assertEquals(BadgeTone.Neutral, toneOf(row(layer = CaptureLayer.CAPTURE_LAYER_CONNECTION)))
    }

    @Test
    fun `a start time shows to the millisecond in this machine's zone`() {
        val ms = 1_761_400_000_123L
        val expected = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
        assertEquals("$expected.123", formatClock(ms))
    }

    private fun row(
        statusCode: Int? = null,
        failed: Boolean = false,
        grpcStatus: Int? = null,
        layer: CaptureLayer = CaptureLayer.CAPTURE_LAYER_PLATFORM,
    ) = Exchange(
        id = 1,
        sessionId = 1,
        requestId = "r",
        method = "GET",
        url = "https://api.example.com",
        host = "api.example.com",
        statusCode = statusCode,
        failed = failed,
        startedAtMs = 0,
        endedAtMs = null,
        captureLayer = layer,
        error = null,
        frameCount = 0,
        grpcStatus = grpcStatus,
    )
}
