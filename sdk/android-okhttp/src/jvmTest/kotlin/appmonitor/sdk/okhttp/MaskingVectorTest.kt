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
    private val cases: List<JsonObject> =
        Json
            .parseToJsonElement(File(System.getProperty("appmonitor.maskingVector")).readText())
            .jsonObject
            .getValue("cases")
            .jsonArray
            .map { it.jsonObject }

    @Test
    fun everyCaseMasksAsTheVectorSays() {
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case.string("name")
            val masking =
                Masking(
                    AppMonitorOptions(
                        maskedHeaders = case.strings("maskedHeaders"),
                        maskedQueryParameters = case.strings("maskedQueryParameters"),
                        masking = case["masking"]?.jsonPrimitive?.boolean ?: true,
                    ),
                )
            val url = case.string("url").toHttpUrl()
            assertEquals(case.string("maskedUrl"), masking.url(url), name)
            assertEquals(case.headers("maskedRequestHeaders"), masking.headers(headersOf(*case.pairs("requestHeaders"))), name)
            assertEquals(
                case.headers("maskedResponseHeaders"),
                masking.headers(headersOf(*case.pairs("responseHeaders")), base = url),
                name,
            )
        }
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
