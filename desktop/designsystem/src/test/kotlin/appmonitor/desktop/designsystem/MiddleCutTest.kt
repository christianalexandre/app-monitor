package appmonitor.desktop.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class MiddleCutTest {
    // One unit per character, so the width is the length.
    private fun cut(
        text: String,
        maxWidth: Int,
    ) = middleCut(text, maxWidth) { it.length }

    @Test
    fun `a text that fits is kept whole`() {
        assertEquals("https://a.example/x", cut("https://a.example/x", 19))
    }

    @Test
    fun `a text too wide keeps its start and its end around an ellipsis`() {
        assertEquals("https:…items", cut("https://api.example.com/v1/items", 12))
        assertEquals("ab…z", cut("abcdefghijklmnopqrstuvwxyz", 4))
    }

    @Test
    fun `no room leaves the ellipsis alone`() {
        assertEquals("…", cut("abcdef", 0))
    }
}
