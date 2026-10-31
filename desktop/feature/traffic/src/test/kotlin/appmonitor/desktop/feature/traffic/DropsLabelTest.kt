package appmonitor.desktop.feature.traffic

import appmonitor.desktop.domain.Drops
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DropsLabelTest {
    @Test
    fun `the label names each count above zero`() {
        assertEquals("Dropped: 3 buffer full, 1 too large, 2 lost", dropsLabel(Drops(3, 1, 2)))
        assertEquals("Dropped: 2 lost", dropsLabel(Drops(0, 0, 2)))
        assertEquals("Dropped: 1 too large", dropsLabel(Drops(0, 1, 0)))
    }

    @Test
    fun `an unknown lost count is said so`() {
        assertEquals("Dropped: 4 buffer full, lost unknown", dropsLabel(Drops(4, 0, null)))
    }

    @Test
    fun `only a count above zero is a drop`() {
        assertFalse(Drops(0, 0, 0).any)
        assertFalse(Drops(0, 0, null).any, "an unknown lost count alone shows nothing")
        assertTrue(Drops(0, 0, 1).any)
    }
}
