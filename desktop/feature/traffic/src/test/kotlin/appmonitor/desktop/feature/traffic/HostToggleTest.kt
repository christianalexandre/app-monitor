package appmonitor.desktop.feature.traffic

import appmonitor.desktop.domain.ExchangeFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ADR-0209, ADR-0214: a sidebar host hides or shows by the list of hidden hosts, so a host seen later shows.
class HostToggleTest {
    private val hosts = listOf("a.example", "b.example", "c.example")

    @Test
    fun `hiding a host lists only that one`() {
        val hidden = ExchangeFilter().withHost("b.example", shown = false, hosts)
        assertEquals(ExchangeFilter(hiddenHosts = setOf("b.example")), hidden)
        assertFalse(hidden.hides("d.example"), "a host seen later shows")
    }

    @Test
    fun `showing the last hidden host hides none again`() {
        val filter = ExchangeFilter(hiddenHosts = setOf("b.example"))
        assertEquals(ExchangeFilter(), filter.withHost("b.example", shown = true, hosts))
    }

    @Test
    fun `hiding the last host shown hides every host`() {
        val filter = ExchangeFilter(hiddenHosts = setOf("a.example", "c.example"))
        assertEquals(ExchangeFilter(noHost = true), filter.withHost("b.example", shown = false, hosts))
    }

    @Test
    fun `every host hides and shows again`() {
        val filter = ExchangeFilter(text = "items", hiddenHosts = setOf("a.example"))
        val off = filter.withEveryHost(shown = false)
        assertEquals(ExchangeFilter(text = "items", noHost = true), off)
        assertTrue(hosts.all { off.hides(it) })
        assertEquals(ExchangeFilter(text = "items"), off.withEveryHost(shown = true))
    }

    @Test
    fun `showing one host while every host is hidden hides the others`() {
        val shown = ExchangeFilter(noHost = true).withHost("b.example", shown = true, hosts)
        assertEquals(ExchangeFilter(hiddenHosts = setOf("a.example", "c.example")), shown)
        assertFalse(shown.hides("b.example"))
        assertTrue(shown.hides("a.example"))
    }

    @Test
    fun `a filter that picks hosts becomes the list of the others once one is toggled`() {
        val picked = ExchangeFilter(hosts = setOf("a.example", "b.example"))
        assertEquals(ExchangeFilter(hiddenHosts = setOf("b.example", "c.example")), picked.withHost("b.example", shown = false, hosts))
    }
}
