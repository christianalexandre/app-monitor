package appmonitor.sdk.ktor

import appmonitor.sdk.core.AppMonitorOptions
import appmonitor.sdk.core.Masking
import appmonitor.v1.Header
import io.ktor.http.URLBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The same vector is read by sdk/ios MaskingTests and sdk/android-okhttp (ADR-0036 A).
class MaskingVectorTest {
    private val vector = Json.parseToJsonElement(MASKING_VECTOR).jsonObject
    private val cases: List<JsonObject> = vector.getValue("cases").jsonArray.map { it.jsonObject }
    private val restores: List<JsonObject> = vector.getValue("restores").jsonArray.map { it.jsonObject }

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
            // What the plugin reads: the request's URLBuilder, built.
            val url = URLBuilder(case.string("url")).buildString()
            assertEquals(case.string("maskedUrl"), masking.url(url), name)
            assertEquals(case.headers("maskedRequestHeaders"), masking.headers(case.pairs("requestHeaders")), name)
            assertEquals(case.headers("maskedResponseHeaders"), masking.headers(case.pairs("responseHeaders"), base = url), name)
        }
    }

    @Test
    fun everyRestoreGoesBackAsTheVectorSays() {
        assertTrue(restores.isNotEmpty())
        for (case in restores) {
            val name = case.string("name")
            val masking =
                Masking(
                    AppMonitorOptions(
                        maskedHeaders = case.strings("maskedHeaders"),
                        maskedQueryParameters = case.strings("maskedQueryParameters"),
                        masking = case["masking"]?.jsonPrimitive?.boolean ?: true,
                    ),
                )
            assertEquals(case.string("restoredUrl"), masking.restoredUrl(case.string("editedUrl"), case.string("url")), name)
            assertEquals(case.pairs("restoredHeaders"), masking.restoredHeaders(case.headers("editedHeaders"), case.pairs("headers")), name)
        }
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.strings(key: String) = (this[key] as JsonArray?).orEmpty().map { it.jsonPrimitive.content }.toSet()

    private fun JsonObject.pairs(key: String) =
        (this[key] as JsonArray?).orEmpty().map { pair ->
            val (name, value) = pair.jsonArray.map { it.jsonPrimitive.content }
            name to value
        }

    private fun JsonObject.headers(key: String) = pairs(key).map { (name, value) -> Header(name, value) }
}
