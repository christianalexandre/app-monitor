package appmonitor.desktop.feature.traffic

import appmonitor.desktop.domain.Exchange
import appmonitor.v1.CaptureLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaletteEntriesTest {
    @Test
    fun `one endpoint per method and path, the query left aside`() {
        val rows =
            listOf(
                row(1, "GET", "https://api.example.com/v1/items?page=1"),
                row(2, "GET", "https://api.example.com/v1/items?page=2"),
                row(3, "POST", "https://api.example.com/v1/items"),
                row(4, "GET", "https://api.example.com/v1/me"),
            )
        assertEquals(listOf(1L, 3L, 4L), endpointsOf(rows, "").map { it.id })
    }

    @Test
    fun `the text matches the method and the URL, case aside`() {
        val rows =
            listOf(
                row(1, "GET", "https://api.example.com/v1/items"),
                row(2, "POST", "https://auth.example.com/oauth/token"),
            )
        assertEquals(listOf(2L), endpointsOf(rows, "post").map { it.id })
        assertEquals(listOf(2L), endpointsOf(rows, "OAUTH").map { it.id })
        assertTrue(endpointsOf(rows, "nothing").isEmpty())
    }

    @Test
    fun `five at most, rows without a URL left out`() {
        val rows = (1L..8L).map { row(it, "GET", "https://example.com/$it") } + row(9, "GET", null)
        assertEquals((1L..5L).toList(), endpointsOf(rows, "").map { it.id })
        assertTrue(endpointsOf(listOf(row(9, "GET", null)), "").isEmpty())
    }

    @Test
    fun `an entry matches by its label`() {
        assertTrue(matches("Export HAR", ""))
        assertTrue(matches("Export HAR", " har "))
        assertTrue(!matches("Export HAR", "issue"))
    }

    private fun row(
        id: Long,
        method: String,
        url: String?,
    ) = Exchange(id, 1, "req-$id", method, url, null, 200, false, 0, 10, CaptureLayer.CAPTURE_LAYER_PLATFORM, null, 0)
}
