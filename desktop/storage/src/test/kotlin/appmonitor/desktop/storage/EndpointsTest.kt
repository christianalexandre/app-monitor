package appmonitor.desktop.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class EndpointsTest {
    @Test
    fun nearestRankPercentile() {
        val values = (1L..20L).toList()
        assertEquals(10, values.percentile(50))
        assertEquals(19, values.percentile(95))
        assertEquals(20, values.percentile(99))
        assertEquals(99, (1L..100L).toList().percentile(99))
        assertEquals(7, listOf(7L).percentile(95))
        assertEquals(1, listOf(1L, 2L).percentile(50))
    }
}
