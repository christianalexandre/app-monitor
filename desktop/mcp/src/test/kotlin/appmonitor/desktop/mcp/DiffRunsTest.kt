package appmonitor.desktop.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class DiffRunsTest {
    private class Runs(
        val base: Long,
        val run: Long,
    )

    /**
     * Base and run share `/users` (an error added), `/orders` (p95 100 → 130 ms), `/stable`, `/small` (100 → 115 ms,
     * under 20 %) and `/tiny` (5 → 9 ms, under 10 ms); `/gone` is in base only, `/added` and `POST /users` in run only.
     */
    private suspend fun Running.seed(): Runs {
        var at = 0L

        suspend fun exchange(
            session: String,
            id: String,
            path: String,
            status: Int,
            durationMs: Long,
            method: String = "GET",
        ) {
            at += 1_000
            storage.received(session, envelope(request(id, method, "https://api.example.com$path"), at))
            storage.received(session, envelope(response(id, status), at + durationMs))
        }
        storage.connected("s1", hello("d1"), null)
        exchange("s1", "a1", "/users", 200, 100)
        exchange("s1", "a2", "/users", 200, 100)
        exchange("s1", "a3", "/orders", 200, 100)
        exchange("s1", "a4", "/stable", 200, 100)
        exchange("s1", "a5", "/small", 200, 100)
        exchange("s1", "a6", "/tiny", 200, 5)
        exchange("s1", "a7", "/gone", 200, 100)
        storage.disconnected("s1")
        val base = storage.runId
        val run = storage.newRun()
        storage.connected("s2", hello("d1"), null)
        exchange("s2", "b1", "/users", 200, 100)
        exchange("s2", "b2", "/users", 500, 100)
        exchange("s2", "b3", "/orders", 200, 130)
        exchange("s2", "b4", "/stable", 200, 100)
        exchange("s2", "b5", "/small", 200, 115)
        exchange("s2", "b6", "/tiny", 200, 9)
        exchange("s2", "b7", "/added", 200, 100)
        exchange("s2", "b8", "/users", 201, 100, method = "POST")
        storage.flush()
        return Runs(base, run)
    }

    private suspend fun Running.diff(arguments: Map<String, Any?>): JsonObject = client { it.callTool(DiffRuns.NAME, arguments) }.json

    @Test
    fun addedGoneChangedAndUnchanged() =
        withServer { running ->
            val runs = running.seed()
            val result = running.diff(mapOf("base_run_id" to runs.base, "run_id" to runs.run))
            assertEquals(runs.base, result.long("base_run_id"))
            assertEquals(runs.run, result.long("run_id"))
            assertEquals(listOf(listOf("GET", "/added"), listOf("POST", "/users")), result.list("added").map { it.key() })
            assertEquals(2, result.long("added_count"))
            assertEquals(listOf(listOf("GET", "/gone")), result.list("gone").map { it.key() })
            assertEquals(1, result.long("gone_count"))
            assertEquals(1, result.list("gone").single().long("count"), "gone and added are endpoint stats")
            assertEquals(3, result.long("unchanged"), "stable, small under 20 %, tiny under 10 ms")
            assertEquals(false, result["truncated"]!!.jsonPrimitive.boolean)

            val (users, orders) = result.list("changed")
            assertEquals(2, result.long("changed_count"))
            assertEquals(listOf("GET", "/users"), users.key(), "largest error rate change first")
            assertEquals("api.example.com", users["host"]!!.jsonPrimitive.content)
            assertEquals(Triple(0.0, 0.5, 0.5), users.doubles("error_rate"))
            assertEquals(Triple(2L, 2L, 0L), users.longs("count"))
            assertEquals(Triple(100L, 100L, 0L), users.longs("p95_ms"))

            assertEquals(listOf("GET", "/orders"), orders.key())
            assertEquals(Triple(0.0, 0.0, 0.0), orders.doubles("error_rate"))
            assertEquals(Triple(100L, 130L, 30L), orders.longs("p50_ms"))
            assertEquals(Triple(100L, 130L, 30L), orders.longs("p95_ms"))
        }

    @Test
    fun filtersApplyToBothRunsAndLimitCutsEachList() =
        withServer { running ->
            val runs = running.seed()
            val posts = running.diff(mapOf("base_run_id" to runs.base, "run_id" to runs.run, "method" to "POST"))
            assertEquals(listOf(listOf("POST", "/users")), posts.list("added").map { it.key() })
            assertEquals(0, posts.long("gone_count"))
            assertEquals(0, posts.long("unchanged"))

            val one = running.diff(mapOf("base_run_id" to runs.base, "run_id" to runs.run, "limit" to 1))
            assertEquals(1, one.list("added").size)
            assertEquals(2, one.long("added_count"))
            assertEquals(1, one.list("changed").size)
            assertEquals(2, one.long("changed_count"))

            val same = running.diff(mapOf("base_run_id" to runs.base, "run_id" to runs.base))
            assertEquals(6, same.long("unchanged"), "a run against itself")
            assertEquals(0, same.long("changed_count"))
        }

    @Test
    fun aDurationKnownOnOneSideOnlyIsAChange() =
        withStorage { storage ->
            val base = storage.runId
            storage.connected("s1", hello(), null)
            storage.received("s1", envelope(request("a"), 1_000))
            storage.received("s1", envelope(response("a", 200), 1_050))
            val run = storage.newRun()
            storage.connected("s2", hello(), null)
            storage.received("s2", envelope(request("b", url = "https://api.example.com/items/a"), 2_000))
            storage.flush()
            val result = DiffRuns.call(storage, args(base, run))
            val change = result.list("changed").single()
            assertEquals(Triple(50L, null, null), change.longsOrNull("p50_ms"), "in flight in run")
        }

    @Test
    fun readCapSaysTruncated() =
        withStorage { storage ->
            storage.connected("s1", hello(), null)
            repeat(3) { storage.received("s1", envelope(request("r$it"))) }
            storage.flush()
            val result = DiffRuns.call(storage, args(storage.runId, storage.runId), maxExchanges = 2)
            assertEquals(true, result["truncated"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun unknownRunMissingArgumentsAndClosingStoreAreErrors() =
        withServer { running ->
            val runs = running.seed()
            running.client { client ->
                val missing = client.callTool(DiffRuns.NAME, mapOf("run_id" to runs.run))
                assertEquals(true, missing.isError)
                assertEquals("base_run_id is required", missing.text)
                assertEquals("run_id is required", client.callTool(DiffRuns.NAME, mapOf("base_run_id" to runs.base)).text)
                assertEquals("no run with id 99", client.callTool(DiffRuns.NAME, mapOf("base_run_id" to runs.base, "run_id" to 99)).text)
            }
            running.storage.close(5.seconds)
            val closed = assertFailsWith<ToolError> { DiffRuns.call(running.storage, args(runs.base, runs.run)) }
            assertEquals(CLOSING, closed.message)
        }

    private fun args(
        base: Long,
        run: Long,
    ) = JsonObject(mapOf("base_run_id" to JsonPrimitive(base), "run_id" to JsonPrimitive(run)))

    private fun JsonObject.list(name: String) = this[name]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.long(name: String) = this[name]!!.jsonPrimitive.long

    private fun JsonObject.key() = listOf("method", "path").map { this[it]!!.jsonPrimitive.content }

    private fun JsonObject.longs(name: String) = this[name]!!.jsonObject.let { Triple(it.long("base"), it.long("run"), it.long("delta")) }

    private fun JsonObject.longsOrNull(name: String) =
        this[name]!!.jsonObject.let { o ->
            fun of(key: String) = o[key]!!.takeUnless { it == JsonNull }?.jsonPrimitive?.long
            Triple(of("base"), of("run"), of("delta"))
        }

    private fun JsonObject.doubles(name: String) =
        this[name]!!.jsonObject.let { o ->
            Triple(o["base"]!!.jsonPrimitive.double, o["run"]!!.jsonPrimitive.double, o["delta"]!!.jsonPrimitive.double)
        }
}
