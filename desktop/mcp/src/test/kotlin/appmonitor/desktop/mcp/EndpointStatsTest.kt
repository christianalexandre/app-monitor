package appmonitor.desktop.mcp

import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.Storage
import appmonitor.v1.RequestFailed
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class EndpointStatsTest {
    /** The first run holds 7 exchanges on 3 endpoints with known numbers; the second, one more on `/users/1`. */
    private suspend fun Running.seed(): Long {
        storage.connected("s1", hello("d1"), null)
        storage.received("s1", envelope(request("a", url = "https://api.example.com/users/1?page=2"), 1_000))
        storage.received("s1", envelope(response("a", 200, body = "0123456789".encodeUtf8()), 1_100))
        storage.received("s1", envelope(request("b", url = "http://API.example.com/users/1#top"), 2_000))
        storage.received("s1", envelope(response("b", 404, body = "x".repeat(20).encodeUtf8()), 2_300))
        storage.received("s1", envelope(request("c", url = "wss://api.example.com/users/1"), 3_000))
        storage.received("s1", envelope(response("c", 101), 3_050))
        storage.received("s1", envelope(request("d", url = "https://api.example.com/users/1"), 4_000))
        storage.received("s1", envelope(RequestFailed(request_id = "d", error = "timeout"), 5_000))
        storage.received("s1", envelope(request("e", "POST", "https://api.example.com/orders", body = "1234567".encodeUtf8()), 6_000))
        storage.received("s1", envelope(response("e", 201), 6_020))
        storage.received("s1", envelope(request("f", "POST", "https://api.example.com/orders", body = "123".encodeUtf8()), 7_000))
        storage.received("s1", envelope(response("f", 503), 7_040))
        storage.received("s1", envelope(request("g", url = "https://cdn.example.org"), 8_000))
        storage.disconnected("s1")
        val firstRun = storage.runId
        storage.newRun()
        storage.connected("s2", hello("d1"), null)
        storage.received("s2", envelope(request("h", url = "https://api.example.com/users/1"), 9_000))
        storage.received("s2", envelope(response("h", 500), 9_010))
        storage.flush()
        return firstRun
    }

    /** 200 exchanges on one endpoint lasting 1 to 200 ms, every 20th a 500: p95 190, p99 198, error rate 0.05. */
    private suspend fun Storage.seedDurations() {
        connected("s1", hello("d1"), null)
        for (i in 1..200) {
            received("s1", envelope(request("r$i", url = "https://api.example.com/search?q=$i"), i * 1_000L))
            received("s1", envelope(response("r$i", if (i % 20 == 0) 500 else 200), i * 1_000L + i))
        }
        disconnected("s1")
        flush()
    }

    private suspend fun Running.stats(arguments: Map<String, Any?>): JsonObject = client { it.callTool(EndpointStats.NAME, arguments) }.json

    private fun JsonObject.endpoints() = this["endpoints"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.long(name: String) = this[name]!!.jsonPrimitive.long

    @Test
    fun knownNumbersPerEndpointMostCalledFirst() =
        withServer { running ->
            val run = running.seed()
            val result = running.stats(mapOf("run_id" to run))
            assertEquals(7, result.long("exchanges_read"))
            assertEquals(false, result["truncated"]!!.jsonPrimitive.boolean)
            assertEquals(3, result.long("endpoint_count"))
            val (users, orders, cdn) = result.endpoints()
            assertEquals(listOf("GET", "api.example.com", "/users/1"), users.key(), "schemes, host case, query and fragment grouped")
            assertEquals(4, users.long("count"))
            assertEquals(mapOf("1xx" to 1L, "2xx" to 1L, "3xx" to 0L, "4xx" to 1L, "5xx" to 0L, "failed" to 1L), users.status())
            assertEquals(0.5, users["error_rate"]!!.jsonPrimitive.double)
            assertEquals(mapOf("min" to 50L, "p50" to 100L, "p95" to 1_000L, "p99" to 1_000L, "max" to 1_000L), users.durations())
            assertEquals(0, users.long("request_bytes"))
            assertEquals(30, users.long("response_bytes"))

            assertEquals(listOf("POST", "api.example.com", "/orders"), orders.key())
            assertEquals(2, orders.long("count"))
            assertEquals(0.5, orders["error_rate"]!!.jsonPrimitive.double)
            assertEquals(mapOf("min" to 20L, "p50" to 20L, "p95" to 40L, "p99" to 40L, "max" to 40L), orders.durations())
            assertEquals(10, orders.long("request_bytes"))
            assertEquals(0, orders.long("response_bytes"))

            assertEquals(listOf("GET", "cdn.example.org", "/"), cdn.key(), "a URL without a path is /")
            assertEquals(1, cdn.long("count"))
            assertEquals(0.0, cdn["error_rate"]!!.jsonPrimitive.double, "an exchange in flight is no error")
            assertEquals(JsonNull, cdn["duration_ms"], "no exchange with both times")
            assertEquals(mapOf("1xx" to 0L, "2xx" to 0L, "3xx" to 0L, "4xx" to 0L, "5xx" to 0L, "failed" to 0L), cdn.status())
        }

    @Test
    fun filterAndLimit() =
        withServer { running ->
            running.seed()
            val all = running.stats(emptyMap())
            assertEquals(8, all.long("exchanges_read"))
            assertEquals(5, all.endpoints().first().long("count"), "the second run's exchange is in when no run is set")
            val one = running.stats(mapOf("limit" to 1, "status_class" to "5xx"))
            assertEquals(2, one.long("endpoint_count"))
            assertEquals(1, one.endpoints().size)
            assertEquals(1, one.endpoints().single().long("count"))
        }

    @Test
    fun readsStopAtTheCapAndSayTruncated() =
        withStorage { storage ->
            storage.connected("s1", hello(), null)
            repeat(7) { storage.received("s1", envelope(request("r$it"))) }
            storage.flush()
            val cut = EndpointStats.call(storage, null, maxExchanges = 3)
            assertEquals(3, cut.long("exchanges_read"))
            assertEquals(true, cut["truncated"]!!.jsonPrimitive.boolean)
            val exact = EndpointStats.call(storage, null, maxExchanges = 7)
            assertEquals(7, exact.long("exchanges_read"))
            assertEquals(false, exact["truncated"]!!.jsonPrimitive.boolean, "nothing left past the cap")
            assertEquals(7, EndpointStats.group(storage, ExchangeFilter(), maxExchanges = 300).exchangesRead)
        }

    @Test
    fun closingStoreAndBadArgumentsAreErrors() =
        withServer { running ->
            running.client { client ->
                val result = client.callTool(EndpointStats.NAME, mapOf("limit" to 0))
                assertEquals(true, result.isError)
                assertEquals("limit must be an integer from 1 to 500", result.text)
            }
            running.storage.close(5.seconds)
            val closed = assertFailsWith<ToolError> { EndpointStats.call(running.storage, null) }
            assertEquals(CLOSING, closed.message)
        }

    @Test
    fun knownP99() =
        withServer { running ->
            running.storage.seedDurations()
            val search = running.stats(mapOf("run_id" to running.storage.runId)).endpoints().single()
            assertEquals(mapOf("min" to 1L, "p50" to 100L, "p95" to 190L, "p99" to 198L, "max" to 200L), search.durations())
            assertEquals(0.05, search["error_rate"]!!.jsonPrimitive.double)
        }

    private fun JsonObject.key() = listOf("method", "host", "path").map { this[it]!!.jsonPrimitive.content }

    private fun JsonObject.status() = this["status"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long }

    private fun JsonObject.durations() = this["duration_ms"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long }
}
