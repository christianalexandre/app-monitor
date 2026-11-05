package appmonitor.sdk.okhttp

import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.v1.Header
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The same file is read by sdk/ios MaskingTests (ADR-0036 A).
class MaskingVectorTest {
    private val vector = Json.parseToJsonElement(File(System.getProperty("appmonitor.maskingVector")).readText()).jsonObject
    private val cases: List<JsonObject> = vector.getValue("cases").jsonArray.map { it.jsonObject }
    private val restores: List<JsonObject> = vector.getValue("restores").jsonArray.map { it.jsonObject }

    @Test
    fun everyCaseMasksAsTheVectorSays() {
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case.string("name")
            val masking =
                okHttpMasking(
                    AppMonitorOptions(
                        maskedHeaders = case.strings("maskedHeaders"),
                        maskedQueryParameters = case.strings("maskedQueryParameters"),
                        masking = case["masking"]?.jsonPrimitive?.boolean ?: true,
                    ),
                )
            // What the interceptor reads: the request's HttpUrl, as a string.
            val url = case.string("url").toHttpUrl().toString()
            assertEquals(case.string("maskedUrl"), masking.url(url), name)
            assertEquals(case.headers("maskedRequestHeaders"), masking.headers(headersOf(*case.pairs("requestHeaders")).toList()), name)
            assertEquals(
                case.headers("maskedResponseHeaders"),
                masking.headers(headersOf(*case.pairs("responseHeaders")).toList(), base = url),
                name,
            )
        }
    }

    @Test
    fun everyRestoreGoesBackAsTheVectorSays() {
        assertTrue(restores.isNotEmpty())
        for (case in restores) {
            val name = case.string("name")
            val masking =
                okHttpMasking(
                    AppMonitorOptions(
                        maskedHeaders = case.strings("maskedHeaders"),
                        maskedQueryParameters = case.strings("maskedQueryParameters"),
                        masking = case["masking"]?.jsonPrimitive?.boolean ?: true,
                    ),
                )
            assertEquals(case.string("restoredUrl"), masking.restoredUrl(case.string("editedUrl"), case.string("url")), name)
            assertEquals(
                case.headers("restoredHeaders").map { it.name to it.value_ },
                masking.restoredHeaders(case.headers("editedHeaders"), case.headers("headers").map { it.name to it.value_ }),
                name,
            )
        }
    }

    @Test
    fun aValueWithNeitherSchemeNorAuthorityIsResolved() {
        val masking = okHttpMasking(AppMonitorOptions(maskedQueryParameters = setOf("code")))
        val base = "https://auth.example.com/authorize"
        assertEquals(
            listOf(Header("Location", "https://auth.example.com/not%20a%20url?code=██")),
            masking.headers(listOf("Location" to "not a url?code=1"), base = base),
        )
    }

    @Test
    fun aLocationResolvesAsOkHttpFollowsIt() {
        val masking = okHttpMasking(AppMonitorOptions(maskedQueryParameters = setOf("code")))
        assertEquals(
            listOf(Header("Location", "https://██:██@h.example/x?code=██")),
            masking.headers(listOf("Location" to "https:\\\\user:pw@h.example\\x?code=1"), base = "https://a.example/"),
        )
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.strings(key: String) = (this[key] as JsonArray?).orEmpty().map { it.jsonPrimitive.content }.toSet()

    private fun JsonObject.pairs(key: String) =
        (this[key] as JsonArray?)
            .orEmpty()
            .flatMap {
                it.jsonArray.map { v -> v.jsonPrimitive.content }
            }.toTypedArray()

    private fun JsonObject.headers(key: String) = pairs(key).toList().chunked(2) { (name, value) -> Header(name, value) }
}
