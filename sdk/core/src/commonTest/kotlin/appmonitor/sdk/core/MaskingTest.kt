package appmonitor.sdk.core

import appmonitor.v1.Header
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The rule itself is pinned by protocol/fixtures/masking.json, in each capture module's MaskingVectorTest.
@OptIn(InternalAppMonitorApi::class)
class MaskingTest {
    @Test
    fun relativeReferencesResolveByRfc3986() {
        val base = "https://a.example/b/c/d;p?q"
        // RFC 3986 5.4.1 and 5.4.2.
        for ((reference, resolved) in listOf(
            "g" to "https://a.example/b/c/g",
            "./g" to "https://a.example/b/c/g",
            "g/" to "https://a.example/b/c/g/",
            "/g" to "https://a.example/g",
            "//g" to "https://g",
            "?y" to "https://a.example/b/c/d;p?y",
            "g?y" to "https://a.example/b/c/g?y",
            "#s" to "https://a.example/b/c/d;p?q#s",
            "" to "https://a.example/b/c/d;p?q",
            ".." to "https://a.example/b/",
            "../../../g" to "https://a.example/g",
            "/./g" to "https://a.example/g",
            "g/../h" to "https://a.example/b/c/h",
        )) {
            assertEquals(resolved, resolve(base, reference), reference)
        }
        assertNull(resolve(base, "http://h/x"))
        assertNull(resolve(base, "//user:pw@h:p/x"))
        assertNull(resolve(base, "//h:70000/x"))
    }

    @Test
    fun aMaskedQueryKeepsTheOtherFieldsAsWritten() {
        val masking = Masking(AppMonitorOptions(maskedQueryParameters = setOf("token")))
        assertEquals("https://h.example/?q=a+b&token=██&r=%41", masking.url("https://h.example/?q=a+b&token=1&r=%41"))
    }

    @Test
    fun aResponseUrlHeaderGoesThroughTheResolverGiven() {
        val masking =
            Masking(AppMonitorOptions(maskedQueryParameters = setOf("code"))) { base, reference -> "$base|$reference" }
        assertEquals(
            listOf(Header("Location", "https://h/|x?code=██")),
            masking.headers(listOf("Location" to "x?code=1"), base = "https://h/"),
        )
    }
}
