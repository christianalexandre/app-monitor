package appmonitor.desktop.mcp

import appmonitor.desktop.storage.LiveTraffic
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveTrafficToolsTest {
    @Test
    fun toolsReadTheLiveListUnlessARunIsGiven() {
        val live = LiveTraffic()
        withServer(live = live) { running ->
            val storage = running.storage
            storage.connected("stored", hello("d1"), null)
            storage.received("stored", envelope(request("stored", url = "https://api.example.com/stored")))
            storage.disconnected("stored")
            storage.flush()
            live.connected("s1", hello("d1"), null)
            live.connected("s2", hello("d2"), null)
            live.received("s1", envelope(request("a"), 1_000))
            live.received("s1", envelope(response("a", 200), 1_200))
            live.received("s2", envelope(request("b"), 2_000))

            running.client { client ->
                val rows =
                    client
                        .callTool(SearchRequests.NAME, emptyMap())
                        .json["requests"]!!
                        .jsonArray
                        .map { it.jsonObject }
                assertEquals(
                    listOf("https://api.example.com/items/a", "https://api.example.com/items/b"),
                    rows.map { it["url"]!!.jsonPrimitive.content },
                )
                val id = rows.first()["id"]!!.jsonPrimitive.long
                assertTrue(LiveTraffic.isLive(id))
                assertEquals(
                    200,
                    client
                        .callTool(GetRequest.NAME, mapOf("id" to id))
                        .json["status_code"]!!
                        .jsonPrimitive.long,
                )

                val stored = client.callTool(SearchRequests.NAME, mapOf("run_id" to storage.runId)).json["requests"]!!.jsonArray
                assertEquals(
                    "https://api.example.com/stored",
                    stored
                        .single()
                        .jsonObject["url"]!!
                        .jsonPrimitive.content,
                )

                val endpoints = client.callTool(EndpointStats.NAME, emptyMap()).json["endpoints"]!!.jsonArray
                assertEquals(listOf("/items/a", "/items/b"), endpoints.map { it.jsonObject["path"]!!.jsonPrimitive.content }.sorted())
            }
        }
    }
}
