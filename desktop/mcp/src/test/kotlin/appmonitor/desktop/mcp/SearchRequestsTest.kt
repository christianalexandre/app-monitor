package appmonitor.desktop.mcp

import appmonitor.v1.RequestFailed
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchRequestsTest {
    /** Two sessions in two runs: d1/app.one in the first, d2/app.two in the second. Returns the exchange ids by request id. */
    private suspend fun Running.seed(): Seeded {
        storage.connected("s1", hello("d1").copy(app_id = "app.one"), null)
        storage.received("s1", envelope(request("get-ok", url = "https://api.example.com/users/1"), 1_000))
        storage.received("s1", envelope(response("get-ok", 200), 1_250))
        storage.received("s1", envelope(request("post-404", method = "POST", url = "https://api.example.com/orders"), 2_000))
        storage.received("s1", envelope(response("post-404", 404), 2_100))
        storage.received("s1", envelope(request("get-fail", url = "https://cdn.example.org/img.png"), 3_000))
        storage.received("s1", envelope(RequestFailed(request_id = "get-fail", error = "timeout"), 3_500))
        storage.disconnected("s1")
        val firstRun = storage.runId
        val secondRun = storage.newRun()
        storage.connected("s2", hello("d2").copy(app_id = "app.two"), null)
        storage.received("s2", envelope(request("get-500", url = "https://API.example.com/Users/2"), 4_000))
        storage.received("s2", envelope(response("get-500", 503), 4_010))
        storage.flush()
        val sessions = storage.sessions(firstRun) + storage.sessions(secondRun)
        return Seeded(firstRun, secondRun, sessions.map { it.id })
    }

    private class Seeded(
        val firstRun: Long,
        val secondRun: Long,
        val sessions: List<Long>,
    )

    private suspend fun Running.urls(arguments: Map<String, Any?>): List<String> =
        client { it.callTool(SearchRequests.NAME, arguments) }.json.requests().map { it["url"]!!.jsonPrimitive.content }

    @Test
    fun rowFieldsOldestFirstWithoutHeadersOrBodies() =
        withServer { running ->
            running.seed()
            val rows = running.client { it.callTool(SearchRequests.NAME, emptyMap()) }.json.requests()
            assertEquals(4, rows.size)
            val first = rows[0]
            assertEquals(
                setOf(
                    "id",
                    "session_id",
                    "method",
                    "url",
                    "status_code",
                    "failed",
                    "error",
                    "started_at",
                    "duration_ms",
                    "capture_layer",
                    "frame_count",
                ),
                first.keys,
            )
            assertEquals("GET", first["method"]!!.jsonPrimitive.content)
            assertEquals(200, first["status_code"]!!.jsonPrimitive.long)
            assertEquals(250, first["duration_ms"]!!.jsonPrimitive.long)
            assertEquals("1970-01-01T00:00:01.000Z", first["started_at"]!!.jsonPrimitive.content)
            assertEquals("adapter", first["capture_layer"]!!.jsonPrimitive.content)
            val failed = rows[2]
            assertEquals(true, failed["failed"]!!.jsonPrimitive.boolean)
            assertEquals("timeout", failed["error"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, failed["status_code"])
        }

    @Test
    fun oneCasePerFilter() =
        withServer { running ->
            val seeded = running.seed()
            val users1 = "https://api.example.com/users/1"
            val orders = "https://api.example.com/orders"
            val img = "https://cdn.example.org/img.png"
            val users2 = "https://API.example.com/Users/2"
            assertEquals(listOf(users1, users2), running.urls(mapOf("text" to "users")), "text, ASCII case-insensitive")
            assertEquals(listOf(orders), running.urls(mapOf("method" to "POST")))
            assertEquals(listOf(users1), running.urls(mapOf("status_class" to "2xx")))
            assertEquals(listOf(orders), running.urls(mapOf("status_class" to "4xx")))
            assertEquals(listOf(users2), running.urls(mapOf("status_class" to "5xx")))
            assertEquals(listOf(img), running.urls(mapOf("failed_only" to true)))
            assertEquals(listOf(users2), running.urls(mapOf("device_id" to "d2")))
            assertEquals(listOf(users1, orders, img), running.urls(mapOf("app_id" to "app.one")))
            assertEquals(listOf(users1, orders, img), running.urls(mapOf("run_id" to seeded.firstRun)))
            assertEquals(listOf(users2), running.urls(mapOf("run_id" to seeded.secondRun)))
            assertEquals(listOf(users2), running.urls(mapOf("session_id" to seeded.sessions[1])))
            assertEquals(listOf(users1), running.urls(mapOf("text" to "users", "run_id" to seeded.firstRun, "method" to "GET")), "combined")
            assertEquals(emptyList(), running.urls(mapOf("text" to "absent")))
        }

    @Test
    fun pagesAcrossAPageBoundary() =
        withServer { running ->
            val storage = running.storage
            storage.connected("s1", hello(), null)
            repeat(5) { storage.received("s1", envelope(request("r$it"))) }
            storage.flush()
            running.client { client ->
                val first = client.callTool(SearchRequests.NAME, mapOf("limit" to 3)).json
                val firstIds = first.requests().map { it["id"]!!.jsonPrimitive.long }
                assertEquals(3, firstIds.size)
                val next = first["next_after_id"]!!.jsonPrimitive.long
                assertEquals(firstIds.last(), next)
                val second = client.callTool(SearchRequests.NAME, mapOf("limit" to 3, "after_id" to next)).json
                val secondIds = second.requests().map { it["id"]!!.jsonPrimitive.long }
                assertEquals(2, secondIds.size)
                assertTrue(secondIds.first() > firstIds.last())
                assertEquals(JsonNull, second["next_after_id"])
            }
        }

    @Test
    fun aBadArgumentIsAnError() =
        withServer { running ->
            running.client { client ->
                val bad =
                    listOf(
                        mapOf("limit" to 201),
                        mapOf("status_class" to "2XX"),
                        mapOf("failed_only" to "yes"),
                        mapOf("run_id" to 0),
                        mapOf("text" to 5),
                        mapOf("after_id" to -1),
                    )
                for (arguments in bad) {
                    val result = client.callTool(SearchRequests.NAME, arguments)
                    assertEquals(true, result.isError, "$arguments")
                    assertTrue(result.text.startsWith(arguments.keys.single()), result.text)
                }
            }
        }

    private fun JsonObject.requests() = this["requests"]!!.jsonArray.map { it.jsonObject }
}
