package appmonitor.desktop.feature.traffic

import appmonitor.desktop.domain.ExchangeFilter
import kotlin.test.Test
import kotlin.test.assertEquals

// ADR-0205: a sidebar host hides or shows over the store's filter, which keeps the hosts picked.
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
    fun `the last host shown stays shown`() {
        val filter = ExchangeFilter(hosts = setOf("a.example"))
        assertEquals(filter, filter.withHost("a.example", shown = false, hosts))
    }
}
