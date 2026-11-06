package appmonitor.desktop.feature.traffic

import appmonitor.desktop.designsystem.pathCut
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.StatusClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestRowTextTest {
    @Test
    fun `a URL splits into host, path and query`() {
        assertEquals(UrlParts("api.example.com", "/v1/items", "?page=2"), urlPartsOf("https://api.example.com/v1/items?page=2"))
        assertEquals(UrlParts("api.example.com", "/", ""), urlPartsOf("https://api.example.com"))
        assertEquals(UrlParts("api.example.com", "/", "?q=1"), urlPartsOf("http://api.example.com?q=1"))
        assertEquals(UrlParts("10.0.0.7:8443", "/", ""), urlPartsOf("tcp://10.0.0.7:8443"))
        assertEquals(UrlParts("r1", "/", ""), urlPartsOf("r1"))
    }

    @Test
    fun `a long path with a query keeps the path's end in a narrow row`() {
        val parts = urlPartsOf("https://api.example.com/v1/orders/2026/customers/42/items/7?include=tags&sort=price")
        // One unit per character: room for 20, far less than the path and query.
        val (path, query) = pathCut(parts.path, parts.query, 20) { it.length }
        val shown = path + query
        assertTrue(shown.endsWith("/items/7"), shown)
        assertEquals("", query)
        assertEquals("…ustomers/42/items/7", shown)

        val (wide, cutQuery) = pathCut(parts.path, parts.query, parts.path.length + 6) { it.length }
        assertEquals(parts.path, wide)
        assertEquals("?incl…", cutQuery)
    }

    @Test
    fun `a status choice sets one class or failed only, and reads back from the filter`() {
        val filter = ExchangeFilter(text = "items", statusClasses = setOf(StatusClass.SERVER_ERROR), failedOnly = true)
        assertEquals(ExchangeFilter(text = "items"), StatusChoice.All.applyTo(filter))
        assertEquals(
            ExchangeFilter(text = "items", statusClasses = setOf(StatusClass.CLIENT_ERROR)),
            StatusChoice.ClientError.applyTo(filter),
        )
        assertEquals(ExchangeFilter(text = "items", failedOnly = true), StatusChoice.Failed.applyTo(filter))
        assertEquals(StatusChoice.ClientError, StatusChoice.of(StatusChoice.ClientError.applyTo(filter)))
        assertEquals(StatusChoice.All, StatusChoice.of(ExchangeFilter()))
        assertNull(StatusChoice.of(filter))
    }

    @Test
    fun `the count says when the store may hold more`() {
        assertEquals("1 request", countLabel(1, more = false))
        assertEquals("0 requests", countLabel(0, more = false))
        assertEquals("200+ requests", countLabel(200, more = true))
    }
}
