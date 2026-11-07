package appmonitor.desktop.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class PathCutTest {
    // One unit per character, so the width is the length.
    private fun cut(
        path: String,
        query: String,
        maxWidth: Int,
    ) = pathCut(path, query, maxWidth) { it.length }

    @Test
    fun `a path and query that fit are kept whole`() {
        assertEquals("/v1/items" to "?page=2", cut("/v1/items", "?page=2", 16))
    }

    @Test
    fun `the query is cut before the path`() {
        assertEquals("/v1/items" to "?pa…", cut("/v1/items", "?page=2", 13))
        assertEquals("/v1/items" to "…", cut("/v1/items", "?page=2", 10))
    }

    @Test
    fun `then the path's start goes and its end stays`() {
        assertEquals("…items/42" to "", cut("/v1/orders/items/42", "?page=2", 9))
        assertEquals("…items/42" to "", cut("/v1/orders/items/42", "", 9))
    }

    @Test
    fun `no room leaves the ellipsis alone`() {
        assertEquals("…" to "", cut("/v1", "?a=1", 0))
    }
}
