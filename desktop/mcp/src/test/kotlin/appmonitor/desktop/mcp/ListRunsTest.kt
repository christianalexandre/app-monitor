package appmonitor.desktop.mcp

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

class ListRunsTest {
    @Test
    fun newestFirstWithSessionsCountsAndTheRecordingFlagged() =
        withServer { running ->
            val storage = running.storage
            storage.connected("s1", hello("d1"), null)
            storage.received("s1", started("r1"))
            storage.received("s1", started("r2"))
            storage.disconnected("s1")
            val second = storage.record()!!
            storage.connected("s2", hello("d2"), null)
            storage.received("s2", started("r3"))
            storage.flush()

            val runs =
                running
                    .client { it.callTool(ListRuns.NAME, emptyMap()) }
                    .json["runs"]!!
                    .jsonArray
                    .map { it.jsonObject }

            assertEquals(listOf(second, storage.runId), runs.map { it.long("id") })
            assertEquals(listOf(1L, 2L), runs.map { it.long("exchange_count") })
            assertEquals(listOf(true, false), runs.map { it["current"]!!.jsonPrimitive.boolean })
            assertEquals(JsonNull, runs[0]["ended_at"])
            assertEquals("1970-01-01T00:00:01.000Z", runs[1]["started_at"]!!.jsonPrimitive.content)
            val session = runs[1]["sessions"]!!.jsonArray.single().jsonObject
            assertEquals(
                mapOf(
                    "device_id" to "d1",
                    "device_name" to "iPhone 17",
                    "platform" to "ios",
                    "os_version" to "26.0",
                    "app_id" to "dev.appmonitor.sample",
                    "app_version" to "1.2",
                    "sdk_name" to "swift",
                    "connected_at" to "1970-01-01T00:00:01.000Z",
                    "disconnected_at" to "1970-01-01T00:00:01.000Z",
                ),
                session.filterKeys { it != "session_id" }.mapValues { it.value.jsonPrimitive.content },
            )
        }

    @Test
    fun pagesAcrossTheLimit() =
        withServer { running ->
            val ids = listOf(running.storage.runId) + List(4) { running.storage.newRun() }
            running.client { client ->
                val first = client.callTool(ListRuns.NAME, mapOf("limit" to 3)).json
                assertEquals(ids.reversed().take(3), first.runIds())
                val next = first["next_before_id"]!!.jsonPrimitive.long
                val second = client.callTool(ListRuns.NAME, mapOf("limit" to 3, "before_id" to next)).json
                assertEquals(ids.take(2).reversed(), second.runIds())
                assertEquals(JsonNull, second["next_before_id"], "a page short of the limit is the last")
            }
        }

    @Test
    fun anArgumentOutOfRangeIsAnError() =
        withServer { running ->
            running.client { client ->
                for (arguments in listOf(mapOf("limit" to 0), mapOf("limit" to 101), mapOf("limit" to "5"), mapOf("before_id" to -1))) {
                    val result = client.callTool(ListRuns.NAME, arguments)
                    assertEquals(true, result.isError, "$arguments")
                    assertTrue(result.text.startsWith(arguments.keys.single()), result.text)
                }
            }
        }

    private fun JsonObject.long(name: String) = this[name]!!.jsonPrimitive.long

    private fun JsonObject.runIds() = this["runs"]!!.jsonArray.map { it.jsonObject.long("id") }
}
