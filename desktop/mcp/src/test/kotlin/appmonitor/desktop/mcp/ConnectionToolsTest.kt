package appmonitor.desktop.mcp

import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.tools.fakeclient.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// M8.4: the `CONNECTION` fixtures through every tool that reads an exchange (ADR-0103, ADR-0104).
class ConnectionToolsTest {
    private class Stored(
        val base: Long,
        val run: Long,
        val connection: Long,
    )

    private suspend fun Running.store(): Stored {
        storage.connected("s1", hello(), null)
        storage.received("s1", envelope(request("r1")))
        storage.received("s1", envelope(response("r1")))
        storage.disconnected("s1")
        val base = storage.runId
        val run = storage.newRun()
        storage.connected("s2", hello(), null)
        storage.received("s2", envelope(request("r2", url = "https://api.example.com/items/r1")))
        Fixtures.connection.forEach { storage.received("s2", it) }
        storage.flush()
        return Stored(base, run, storage.searchExchanges(ExchangeFilter()).single { it.isConnection }.id)
    }

    @Test
    fun aConnectionHasNoStatusRequestOrResponse() =
        withServer { running ->
            val stored = running.store()
            running.client { client ->
                val rows =
                    client.callTool(SearchRequests.NAME, mapOf("text" to "push.example.com")).json["requests"]!!.jsonArray
                val row = rows.single().jsonObject
                assertEquals(stored.connection, row["id"]!!.jsonPrimitive.content.toLong())
                assertEquals("CONNECT", row["method"]!!.jsonPrimitive.content)
                assertEquals("tcp://push.example.com:5223", row["url"]!!.jsonPrimitive.content)
                assertEquals(JsonNull, row["status_code"])
                assertEquals(1_500L, row["duration_ms"]!!.jsonPrimitive.content.toLong())
                assertEquals("connection", row["capture_layer"]!!.jsonPrimitive.content)

                val detail = client.callTool(GetRequest.NAME, mapOf("id" to stored.connection)).json
                assertEquals(JsonNull, detail["request"])
                assertEquals(JsonNull, detail["response"])
                assertEquals(json("""{"sent_bytes":512,"received_bytes":2048}"""), detail["connection"])
                assertEquals(JsonNull, client.callTool(GetRequest.NAME, mapOf("id" to stored.connection - 1)).json["connection"])

                val curl = client.callTool(ToCurl.NAME, mapOf("id" to stored.connection))
                assertEquals(true, curl.isError)
                assertEquals("exchange ${stored.connection} is a connection: it has no HTTP request to copy", curl.text)
            }
        }

    @Test
    fun benchmarksAndTheRunDiffCountItsBytesPerPort() =
        withServer { running ->
            val stored = running.store()
            running.client { client ->
                val stats = client.callTool(EndpointStats.NAME, mapOf("run_id" to stored.run)).json
                val endpoint = stats["endpoints"]!!.jsonArray.map { it.jsonObject }.single { it["method"] == JsonPrimitive("CONNECT") }
                assertEquals(":5223", endpoint["path"]!!.jsonPrimitive.content)
                assertEquals(512L, endpoint["request_bytes"]!!.jsonPrimitive.content.toLong())
                assertEquals(2_048L, endpoint["response_bytes"]!!.jsonPrimitive.content.toLong())
                assertEquals(0.0, endpoint["error_rate"]!!.jsonPrimitive.content.toDouble())

                val diff = client.callTool(DiffRuns.NAME, mapOf("base_run_id" to stored.base, "run_id" to stored.run)).json
                val added = diff["added"]!!.jsonArray.map { it.jsonObject }
                assertEquals(listOf("push.example.com"), added.map { it["host"]!!.jsonPrimitive.content })
            }
        }

    @Test
    fun theHarLeavesConnectionsOutAndSaysSo() =
        withServer { running ->
            val stored = running.store()
            val log =
                running
                    .client { it.callTool(ExportHar.NAME, mapOf("run_id" to stored.run)) }
                    .json["log"]!!
                    .jsonObject
            val entries = log["entries"] as JsonArray
            assertEquals(
                listOf("https://api.example.com/items/r1"),
                entries.map {
                    it.jsonObject["request"]!!
                        .jsonObject["url"]!!
                        .jsonPrimitive.content
                },
            )
            assertEquals("1 connections left out: HAR holds HTTP exchanges only", log["comment"]!!.jsonPrimitive.content)
            val har = Exports.har(running.storage, ExchangeFilter(runId = stored.run))
            assertEquals(1 to 1, har.entries to har.connections)
            assertTrue(!har.more)
            assertHar12(
                kotlinx.serialization.json.Json
                    .parseToJsonElement(har.text)
                    .jsonObject,
            )
        }

    private fun json(text: String) =
        kotlinx.serialization.json.Json
            .parseToJsonElement(text) as JsonObject
}
