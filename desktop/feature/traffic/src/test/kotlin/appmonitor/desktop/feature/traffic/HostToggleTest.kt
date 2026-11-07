package appmonitor.desktop.feature.traffic

import appmonitor.desktop.domain.ExchangeFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ADR-0205, ADR-0209: a sidebar host hides or shows over the store's filter, which keeps the hosts picked.
class HostToggleTest {
    private val hosts = listOf("a.example", "b.example", "c.example")

    @Test
    fun `hiding a host keeps every other one`() {
        val hidden = ExchangeFilter().withHost("b.example", shown = false, hosts)
        assertEquals(setOf("a.example", "c.example"), hidden.hosts)
    }

    @Test
    fun `showing the last hidden host picks none again`() {
        val filter = ExchangeFilter(hosts = setOf("a.example", "c.example"))
        assertEquals(emptySet(), filter.withHost("b.example", shown = true, hosts).hosts)
    }

    @Test
    fun `hiding the last host shown hides every host`() {
        val filter = ExchangeFilter(hosts = setOf("a.example"))
        assertEquals(ExchangeFilter(noHost = true), filter.withHost("a.example", shown = false, hosts))
    }

    @Test
    fun `every host hides and shows again`() {
        val filter = ExchangeFilter(text = "items", hosts = setOf("a.example"))
        val off = filter.withEveryHost(shown = false)
        assertEquals(ExchangeFilter(text = "items", noHost = true), off)
        assertTrue(hosts.all { off.hides(it) })
        assertEquals(ExchangeFilter(text = "items"), off.withEveryHost(shown = true))
    }

    @Test
    fun `showing one host while every host is hidden picks that one`() {
        val shown = ExchangeFilter(noHost = true).withHost("b.example", shown = true, hosts)
        assertEquals(ExchangeFilter(hosts = setOf("b.example")), shown)
        assertFalse(shown.hides("b.example"))
        assertTrue(shown.hides("a.example"))
    }
}
