package appmonitor.desktop.mcp

import appmonitor.desktop.domain.AskedReplay
import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.tools.fakeclient.Fixtures
import appmonitor.v1.CaptureLayer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ADR-0121: `replay_request` leaves the replay to the window and answers at once; rows link a replay to its original.
class ReplayTest {
    private class Asks(
        var answer: ReplayAnswer = ReplayAnswer.WAITING,
    ) : ReplayAsks {
        val asked = mutableListOf<AskedReplay>()

        override fun ask(replay: AskedReplay): ReplayAnswer = answer.also { asked += replay }
    }

    @Test
    fun aReplayIsLeftToTheWindowAndAnsweredAtOnce() {
        val asks = Asks()
        withServer(replays = asks) { running ->
            running.storage.connected("s1", hello(), null)
            running.storage.received("s1", envelope(request("r1", method = "POST")))
            running.storage.flush()
            val id =
                running.storage
                    .searchExchanges(ExchangeFilter())
                    .single()
                    .id
            val answer = running.client { it.callTool(AskReplay.NAME, mapOf("id" to id)) }
            assertEquals(Json.parseToJsonElement("""{"replay":{"exchange_id":$id,"status":"waiting"}}"""), answer.json)
            val expected =
                AskedReplay(id, "s1", "r1", "POST", "https://api.example.com/items/r1", "iPhone 17", "dev.appmonitor.sample")
            assertEquals(listOf(expected), asks.asked)
        }
    }

    @Test
    fun whatTheWindowCannotTakeIsAnError() {
        val asks = Asks()
        withServer(replays = asks) { running ->
            running.storage.connected("s1", hello(), null)
            running.storage.received("s1", envelope(request("r1")))
            running.storage.received("s1", envelope(request("ws", url = "wss://api.example.com/socket")))
            running.storage.received("s1", envelope(request("proxy").copy(capture_layer = CaptureLayer.CAPTURE_LAYER_PROXY)))
            Fixtures.connection.forEach { running.storage.received("s1", it) }
            running.storage.flush()
            val rows = running.storage.searchExchanges(ExchangeFilter())
            val byRequest = rows.associate { it.requestId to it.id }
            val connection = rows.single { it.isConnection }.id
            val id = byRequest.getValue("r1")
            running.client { client ->
                suspend fun error(arguments: Map<String, Any>): String {
                    val result = client.callTool(AskReplay.NAME, arguments)
                    assertEquals(true, result.isError, result.text)
                    return result.text
                }
                assertEquals("id is required", error(emptyMap()))
                assertEquals("no exchange with id 999", error(mapOf("id" to 999)))
                assertEquals("exchange $connection is a connection: it has no HTTP request to send again", error(mapOf("id" to connection)))
                val ws = byRequest.getValue("ws")
                assertEquals("exchange $ws is a WebSocket: it cannot be sent again", error(mapOf("id" to ws)))
                val proxy = byRequest.getValue("proxy")
                assertEquals(
                    "exchange $proxy went through the desktop's proxy: only an app sends a request again",
                    error(
                        mapOf("id" to proxy),
                    ),
                )
                assertEquals(emptyList(), asks.asked, "nothing the window cannot take is asked")

                asks.answer = ReplayAnswer.SESSION_ENDED
                assertEquals(
                    "exchange $id's session ended: a replay goes through the app, so only a connected one can take it",
                    error(mapOf("id" to id)),
                )
                asks.answer = ReplayAnswer.NOT_TAKEN
                assertEquals("the app of exchange $id does not take replays", error(mapOf("id" to id)))
                asks.answer = ReplayAnswer.TOO_MANY
                assertEquals(
                    "${ReplayAsks.MAX_WAITING} replays already wait for the developer; ask again once they are answered",
                    error(mapOf("id" to id)),
                )
            }
        }
    }

    @Test
    fun withoutTheWindowReplayIsNotAvailable() =
        withServer { running ->
            running.storage.connected("s1", hello(), null)
            running.storage.received("s1", envelope(request("r1")))
            running.storage.flush()
            val id =
                running.storage
                    .searchExchanges(ExchangeFilter())
                    .single()
                    .id
            val result = running.client { it.callTool(AskReplay.NAME, mapOf("id" to id)) }
            assertEquals(true to "replay is not available", result.isError to result.text)
        }

    @Test
    fun rowsLinkAReplayToItsOriginalInItsSession() =
        withServer { running ->
            running.storage.connected("s1", hello(), null)
            running.storage.received("s1", envelope(request("r1")))
            running.storage.received("s1", envelope(request("again").copy(replay_of = "r1")))
            running.storage.received("s1", envelope(request("lost").copy(replay_of = "gone")))
            running.storage.flush()
            val ids = running.storage.searchExchanges(ExchangeFilter()).associate { it.requestId to it.id }
            running.client { client ->
                val rows =
                    client
                        .callTool(SearchRequests.NAME, emptyMap())
                        .json["requests"]!!
                        .jsonArray
                        .associateBy {
                            it.jsonObject["id"]!!
                                .jsonPrimitive.content
                                .toLong()
                        }.mapValues { (_, row) -> row.jsonObject["replay"] to row.jsonObject["replay_of"] }
                assertEquals(
                    mapOf(
                        ids.getValue("r1") to (JsonPrimitive(false) to JsonNull),
                        ids.getValue("again") to (JsonPrimitive(true) to JsonPrimitive(ids.getValue("r1"))),
                        ids.getValue("lost") to (JsonPrimitive(true) to JsonNull),
                    ),
                    rows,
                )
                val detail = client.callTool(GetRequest.NAME, mapOf("id" to ids.getValue("again"))).json
                assertEquals(JsonPrimitive(ids.getValue("r1")), detail["replay_of"])
                assertTrue(detail["replay"]!!.jsonPrimitive.content.toBoolean())
            }
        }
}
