package appmonitor.desktop.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class MatchesTest {
    @Test
    fun `matches are found in any case, left to right, without overlap`() {
        assertEquals(listOf(0..1, 4..5), matchesOf("Abcdab", "ab"))
        assertEquals(listOf(0..1, 2..3), matchesOf("aaaaa", "aa"))
        assertEquals(emptyList(), matchesOf("abc", " "))
        assertEquals(emptyList(), matchesOf("abc", "x"))
    }
}
