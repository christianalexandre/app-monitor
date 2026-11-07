package appmonitor.desktop.feature.traffic

import appmonitor.desktop.domain.CapturedBody
import appmonitor.desktop.domain.ConnectionBytes
import appmonitor.desktop.domain.HttpExchange
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Header
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailTabsTest {
    private val exchange =
        HttpExchange(
            id = 1,
            requestId = "req-1",
            method = "POST",
            url = "https://example.com/items",
            requestHeaders = listOf(Header("Content-Type", "application/json"), Header("Accept", "*/*")),
            requestBody = CapturedBody("{\"a\":1}".encodeUtf8()),
            startedAtMs = 1_700_000_000_000,
            statusCode = 201,
            responseBody = CapturedBody(byteArrayOf(-1, -2).toByteString()),
            endedAtMs = 1_700_000_000_042,
        )

    @Test
    fun `each part is copied as its tab shows it`() {
        assertEquals("https://example.com/items", textOf(exchange, DetailPart.Url))
        assertEquals("Content-Type: application/json\nAccept: */*", textOf(exchange, DetailPart.RequestHeaders))
        assertNull(textOf(exchange, DetailPart.ResponseHeaders))
        assertEquals("{\"a\":1}", textOf(exchange, DetailPart.RequestBody))
        assertTrue(textOf(exchange, DetailPart.ResponseBody)!!.startsWith("00000000  ff fe"))
        assertEquals(
            "Started: 2023-11-14 22:13:20.000\nEnded: 2023-11-14 22:13:20.042\nDuration: 42 ms",
            textOf(exchange, DetailPart.Timing, ZoneOffset.UTC),
        )
    }

    @Test
    fun `a pending exchange's timing has only its start, a connection only summary and timing`() {
        val pending = exchange.copy(endedAtMs = null)
        assertEquals(listOf("Started" to "2023-11-14 22:13:20.000"), timingOf(pending, ZoneOffset.UTC))
        assertEquals(DetailTab.entries, tabsOf(exchange))
        assertEquals(listOf(DetailTab.Summary, DetailTab.Timing), tabsOf(exchange.copy(connection = ConnectionBytes(1, 2))))
    }

    @Test
    fun `the summary's tables split the URL and describe the response, the meta line sums it up`() {
        val cut =
            exchange.copy(
                url = "https://api.example.com:8443/v1/items?page=2&sort=name",
                responseHeaders = listOf(Header("content-type", "application/json; charset=utf-8")),
                responseBody = CapturedBody(ByteArray(1_000).toByteString(), truncated = true, originalSize = 4_096),
                captureLayer = CaptureLayer.CAPTURE_LAYER_ADAPTER,
            )
        assertEquals(
            listOf(
                "Host" to "api.example.com:8443",
                "Path" to "/v1/items",
                "Query" to "page=2&sort=name",
                "Method" to "POST",
                "Scheme" to "https",
            ),
            requestRowsOf(cut),
        )
        assertEquals(
            listOf("Status" to "201", "Duration" to "42 ms", "Body" to "4.0 KB", "Content-Type" to "application/json"),
            responseRowsOf(cut),
        )
        assertEquals("42 ms · 4.0 KB ↓ · Pixel 9 · 22:13:20.000 · Adapter layer", metaOf(cut, "Pixel 9", ZoneOffset.UTC))
        val failed = exchange.copy(url = "http://10.0.2.2/", error = "timeout", statusCode = null, endedAtMs = null)
        assertEquals("Query" to "—", requestRowsOf(failed)[2])
        assertEquals("Status" to "failed — timeout", responseRowsOf(failed)[0])
        assertEquals("2 B ↓ · 22:13:20.000 · Unknown layer", metaOf(failed, null, ZoneOffset.UTC))
        assertEquals(emptyList(), requestRowsOf(exchange.copy(url = null)))
        assertEquals(listOf("999 B", "1.0 KB", "1.5 MB"), listOf(999L, 1_024L, 1_572_864L).map(::sizeLabel))
    }

    @Test
    fun `a search counts its matches in each tab but cURL, in any case`() {
        val texts =
            searchedTexts(exchange, listOf("{", "    \"a\": 1", "}"), ZoneOffset.UTC)
        val none = mapOf(DetailTab.Summary to 0, DetailTab.Headers to 0, DetailTab.Body to 0, DetailTab.Timing to 0, DetailTab.Curl to 0)
        assertEquals(none + mapOf(DetailTab.Headers to 1), matchCounts(texts, "APPLICATION/json"))
        assertEquals(none + mapOf(DetailTab.Body to 1), matchCounts(texts, "\"A\""))
        assertEquals(none + mapOf(DetailTab.Summary to 1, DetailTab.Timing to 1), matchCounts(texts, "42 ms"))
        assertEquals(none + mapOf(DetailTab.Summary to 1), matchCounts(texts, "example.com"))
        assertEquals(emptyMap(), matchCounts(texts, " "))
        assertEquals(emptyList(), searchedTexts(exchange, emptyList())[DetailTab.Curl])
        assertEquals(listOf("No match", "1 match", "2 matches"), listOf(0, 1, 2).map(::matchSummary))
    }

    @Test
    fun `body matches run through the request then the response, each with its chunk and line`() {
        val request = listOf("{\n    \"id\": 1\n}")
        val response = listOf("[", "    {\"id\": 2},\n    {\"ID\": 3}")
        assertEquals(
            listOf(
                BodyMatch(response = false, chunk = 0, inChunk = 0, line = 1),
                BodyMatch(response = true, chunk = 1, inChunk = 0, line = 0),
                BodyMatch(response = true, chunk = 1, inChunk = 1, line = 1),
            ),
            bodyMatches(request, response, "\"id\""),
        )
        assertEquals(emptyList(), bodyMatches(request, response, ""))
    }
}
