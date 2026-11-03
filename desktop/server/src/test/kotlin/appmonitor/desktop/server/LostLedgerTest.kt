package appmonitor.desktop.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LostLedgerTest {
    private val ledger = LostLedger(closedKept = 2)
    private val settled = mutableMapOf<String, Long?>()

    private fun open(id: String) = ledger.opened(id) { settled[id] = it }

    private fun report(
        id: String,
        previous: String,
        written: Long,
    ): Long? {
        var known: Long? = -1
        ledger.reported(id, previous, written) { known = it }
        return known
    }

    @Test
    fun `reconnects while every older socket is open settle in close order`() {
        listOf("a", "b", "c").forEach(::open)
        assertEquals(0, report("a", "", 0))
        assertNull(report("b", "a", 5))
        assertNull(report("c", "b", 4))

        ledger.closed("a", 3)
        assertEquals(mapOf<String, Long?>("b" to 2), settled)
        ledger.closed("b", 1)
        assertEquals(mapOf<String, Long?>("b" to 2, "c" to 5), settled)
    }

    @Test
    fun `a session that closes before its previous one passes the count on when that one does`() {
        listOf("a", "b", "c").forEach(::open)
        report("a", "", 0)
        report("b", "a", 5)
        ledger.closed("b", 1)
        assertNull(report("c", "b", 4))

        ledger.closed("a", 3)
        assertEquals(mapOf<String, Long?>("b" to 2, "c" to 5), settled)
    }

    @Test
    fun `an unknown previous session or one without reports leaves the count unknown`() {
        listOf("a", "b", "c").forEach(::open)
        assertNull(report("a", "another-desktop", 1))
        ledger.closed("b", 7)
        assertNull(report("c", "b", 8))
        assertEquals(emptyMap(), settled)
    }

    @Test
    fun `the first report names the previous session, later ones keep its count`() {
        listOf("a", "b").forEach(::open)
        report("a", "", 0)
        ledger.closed("a", 1)
        assertEquals(1, report("b", "a", 2))
        assertEquals(1, report("b", "a", 100))
    }

    @Test
    fun `only closed sessions are evicted`() {
        listOf("a", "b", "c", "d").forEach(::open)
        report("a", "", 0)
        ledger.closed("b", 0)
        assertEquals(0, report("c", "", 0))
        // "a" stays open past the bound and its next session still finds it.
        assertNull(report("d", "a", 1))
        ledger.closed("a", 1)
        assertEquals(0L, settled["d"])
    }
}
