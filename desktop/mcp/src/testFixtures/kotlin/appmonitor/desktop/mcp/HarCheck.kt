package appmonitor.desktop.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// HAR 1.2 (http://www.softwareishard.com/blog/har-12-spec/): the fields without "optional" or "-1 if not available".
private val required =
    mapOf(
        "log" to listOf("version", "creator", "entries"),
        "creator" to listOf("name", "version"),
        "entry" to listOf("startedDateTime", "time", "request", "response", "cache", "timings"),
        "request" to listOf("method", "url", "httpVersion", "cookies", "headers", "queryString", "headersSize", "bodySize"),
        "response" to
            listOf("status", "statusText", "httpVersion", "cookies", "headers", "content", "redirectURL", "headersSize", "bodySize"),
        "content" to listOf("size", "mimeType"),
        "postData" to listOf("mimeType"),
        "timings" to listOf("send", "wait", "receive"),
        "nameValue" to listOf("name", "value"),
    )

private fun JsonObject.assertFields(
    kind: String,
    where: String,
) = required.getValue(kind).forEach { assertTrue(it in this, "$where.$it missing") }

private fun JsonObject.obj(name: String) = this[name]!!.jsonObject

/** Fails unless [har] holds every HAR 1.2 required field, and no custom one without `_` in `log`. */
fun assertHar12(har: JsonObject) {
    assertEquals(setOf("log"), har.keys)
    val log = har.obj("log")
    log.assertFields("log", "log")
    val logFields = setOf("version", "creator", "browser", "pages", "entries", "comment")
    log.keys.forEach { assertTrue(it in logFields || it.startsWith("_"), "log.$it is not HAR 1.2 and does not start with _") }
    assertEquals("1.2", log["version"]!!.jsonPrimitive.content)
    log.obj("creator").assertFields("creator", "log.creator")
    log["entries"]!!.jsonArray.forEachIndexed { i, element ->
        val entry = element.jsonObject
        entry.assertFields("entry", "entries[$i]")
        assertTrue(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""").matches(entry["startedDateTime"]!!.jsonPrimitive.content))
        assertTrue(entry["time"]!!.jsonPrimitive.double >= 0)
        val request = entry.obj("request")
        request.assertFields("request", "entries[$i].request")
        request["postData"]?.jsonObject?.assertFields("postData", "entries[$i].request.postData")
        val response = entry.obj("response")
        response.assertFields("response", "entries[$i].response")
        response.obj("content").assertFields("content", "entries[$i].response.content")
        for (list in listOf(request["headers"], request["queryString"], request["cookies"], response["headers"], response["cookies"])) {
            list!!.jsonArray.forEach { it.jsonObject.assertFields("nameValue", "entries[$i]") }
        }
        val timings = entry.obj("timings")
        timings.assertFields("timings", "entries[$i].timings")
        listOf("send", "wait", "receive").forEach { assertTrue(timings[it]!!.jsonPrimitive.double >= 0, "$it is never -1") }
        assertEquals(
            entry["time"]!!.jsonPrimitive.double,
            listOf("send", "wait", "receive").sumOf { timings[it]!!.jsonPrimitive.double },
            "time is the sum of the timings",
        )
    }
}
