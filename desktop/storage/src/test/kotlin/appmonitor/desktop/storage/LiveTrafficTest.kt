package appmonitor.desktop.storage

import appmonitor.v1.CaptureLayer
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTrafficTest {
    private fun response(
        requestId: String,
        status: Int,
        body: String = "",
    ) = ResponseReceived(request_id = requestId, status_code = status, body = body.encodeUtf8())

    @Test
    fun twoDevicesShowInOneListInArrivalOrder() =
        runBlocking {
            val live = LiveTraffic { 5_000 }
            live.connected("s1", hello("d1"), "10.0.0.2")
            live.connected("s2", hello("d2"), "10.0.0.3")
            live.received("s1", envelope(started("a"), 1_000))
            live.received("s2", envelope(started("b"), 1_100))
            live.received("s1", envelope(response("a", 200, "[]"), 1_250))

            val rows = live.observeExchanges(ExchangeFilter()).first()
            assertEquals(listOf("a", "b"), rows.map { it.requestId })
            assertTrue(rows.all { LiveTraffic.isLive(it.id) })
            val sessions = live.sessions()
            assertEquals(listOf("d1", "d2"), sessions.map { it.hello.device_id })
            assertEquals(listOf(LiveTraffic.NO_RUN, LiveTraffic.NO_RUN), sessions.map { it.runId })
            assertEquals(sessions[0].id, rows[0].sessionId)
            assertEquals(listOf("b"), live.searchExchanges(ExchangeFilter(deviceId = "d2")).map { it.requestId })
            assertEquals(listOf("a"), live.searchExchanges(ExchangeFilter(sessionId = sessions[0].id)).map { it.requestId })
            assertEquals(emptyList(), live.searchExchanges(ExchangeFilter(runId = 1)))
        }

    @Test
    fun aHostFilterKeepsThatHostAndTheHostsAreListed() =
        runBlocking {
            val live = LiveTraffic { 5_000 }
            live.connected("s1", hello("d1"), null)
            live.connected("s2", hello("d2"), null)
            live.received("s1", envelope(started("a", url = "https://shop.example.com/cart")))
            live.received("s2", envelope(started("b", url = "https://api.example.com/login")))
            live.received("s1", envelope(started("c", url = "https://api.example.com/me")))

            assertEquals(listOf("a"), live.searchExchanges(ExchangeFilter(hosts = setOf("shop.example.com"))).map { it.requestId })
            assertEquals(listOf("api.example.com", "shop.example.com"), live.observeHosts().first())
            val first = live.sessions().first().id
            assertEquals(listOf("api.example.com", "shop.example.com"), live.observeHosts(first).first())
            assertEquals(listOf("api.example.com"), live.observeHosts(live.sessions().last().id).first())
        }

    @Test
    fun eventsRoundTripAsTheStoreGivesThem() =
        runBlocking {
            val live = LiveTraffic { 5_000 }
            live.connected("s1", hello(), null)
            val request = started("r1", body = "{}".encodeUtf8()).copy(mock_rule_id = 7)
            live.received("s1", envelope(request, 1_000))
            live.received(
                "s1",
                envelope(response("r1", 503, "oops").copy(headers = listOf(appmonitor.v1.Header("grpc-status", "14"))), 1_400),
            )
            live.received("s1", envelope(started("r2").copy(replay_of = "r1"), 2_000))
            live.received("s1", envelope(RequestFailed(request_id = "r2", error = "timeout"), 2_500))
            live.received("s1", envelope(started("ws"), 3_000))
            live.received("s1", envelope(WebSocketFrame(request_id = "ws", payload = "hi".encodeUtf8(), payload_size = 2), 3_100))

            val (r1, r2, ws) = live.searchExchanges(ExchangeFilter())
            val detail = live.exchange(r1.id)!!
            assertEquals(request, detail.request)
            assertEquals("oops".encodeUtf8(), detail.response!!.body)
            assertEquals(503, detail.exchange.statusCode)
            assertEquals(14, detail.exchange.grpcStatus)
            assertEquals(400, detail.exchange.endedAtMs!! - detail.exchange.startedAtMs!!)
            assertEquals(7, detail.exchange.mockRuleId)
            assertNull(detail.mockRuleName)
            assertTrue(r2.failed)
            assertEquals("timeout", r2.error)
            assertEquals(r1.id, live.exchange(r2.id)!!.replayOfId)
            assertEquals(mapOf(r2.id to r1.id), live.replayOriginals(listOf(r1, r2)))
            assertEquals(1, ws.frameCount)
            assertEquals(listOf("hi".encodeUtf8()), live.frames(ws.id).map { it.payload })
            assertEquals(
                listOf(2L to 4L),
                live.searchExchangeSizes(ExchangeFilter(statusClasses = setOf(StatusClass.SERVER_ERROR))).map {
                    it.requestBytes to
                        it.responseBytes
                },
            )
            assertEquals(listOf("r2"), live.searchExchanges(ExchangeFilter(failedOnly = true)).map { it.requestId })
            assertEquals(listOf("r1"), live.searchExchanges(ExchangeFilter(text = "ITEMS/R1")).map { it.requestId })
            assertEquals(5_000, live.sessions().single().lastEnvelopeAtMs)
        }

    @Test
    fun clearEmptiesTheListAndKeepsConnectedSessions() =
        runBlocking {
            val live = LiveTraffic { 5_000 }
            live.connected("s1", hello("d1"), null)
            live.connected("s2", hello("d2"), null)
            live.received("s1", envelope(started("a")))
            live.received("s2", envelope(started("b")))
            live.disconnected("s2")
            val before = live.searchExchanges(ExchangeFilter()).first().id

            live.clear()

            assertEquals(emptyList(), live.observeExchanges(ExchangeFilter()).first())
            assertNull(live.exchange(before))
            assertEquals(listOf("d1"), live.sessions().map { it.hello.device_id })
            live.received("s1", envelope(started("a")))
            assertEquals(listOf("a"), live.searchExchanges(ExchangeFilter()).map { it.requestId })
            assertTrue(live.searchExchanges(ExchangeFilter()).single().id > before)
        }

    @Test
    fun theBoundsDropTheOldestFirst() =
        runBlocking {
            val byCount = LiveTraffic(maxExchanges = 3)
            byCount.connected("s1", hello("d1"), null)
            byCount.connected("s2", hello("d2"), null)
            byCount.received("s2", envelope(started("old")))
            byCount.disconnected("s2")
            listOf("a", "b", "c").forEach { byCount.received("s1", envelope(started(it))) }
            assertEquals(listOf("a", "b", "c"), byCount.searchExchanges(ExchangeFilter()).map { it.requestId })
            // The ended session went with its last exchange.
            assertEquals(listOf("d1"), byCount.sessions().map { it.hello.device_id })

            val byBytes = LiveTraffic(maxBytes = 10)
            byBytes.connected("s1", hello(), null)
            byBytes.received("s1", envelope(started("a", body = text(6))))
            byBytes.received("s1", envelope(started("b", body = text(3))))
            byBytes.received("s1", envelope(response("b", 200, "1234")))
            assertEquals(listOf("b"), byBytes.searchExchanges(ExchangeFilter()).map { it.requestId })
        }

    @Test
    fun emptyFramesCountTowardTheBytes() =
        runBlocking {
            val live = LiveTraffic(maxBytes = 1024)
            live.connected("s1", hello(), null)
            live.received("s1", envelope(started("ws")))
            repeat(1_000) { live.received("s1", envelope(WebSocketFrame(request_id = "ws"))) }

            val ws = live.searchExchanges(ExchangeFilter()).single()
            assertEquals(1_000, ws.frameCount)
            assertTrue(live.frames(ws.id, limit = 10_000).size < 1_000)
        }

    @Test
    fun aWebSocketKeepsItsLastFramesWithinItsShare() =
        runBlocking {
            val byCount = LiveTraffic(maxFramesPerExchange = 3)
            byCount.connected("s1", hello(), null)
            byCount.received("s1", envelope(started("ws")))
            repeat(5) { byCount.received("s1", envelope(WebSocketFrame(request_id = "ws", payload = "$it".encodeUtf8()))) }
            val ws = byCount.searchExchanges(ExchangeFilter()).single()
            assertEquals(5, ws.frameCount)
            assertEquals(listOf("2", "3", "4"), byCount.frames(ws.id).map { it.payload.utf8() })

            // A quarter of the bytes holds two empty frames; the exchange stays, its request too.
            val byBytes = LiveTraffic(maxBytes = LiveTraffic.FRAME_OVERHEAD_BYTES * 8 + 1)
            byBytes.connected("s1", hello(), null)
            byBytes.received("s1", envelope(started("ws")))
            repeat(5) { byBytes.received("s1", envelope(WebSocketFrame(request_id = "ws"))) }
            val kept = byBytes.searchExchanges(ExchangeFilter()).single()
            assertEquals(2, byBytes.frames(kept.id).size)
            assertEquals("ws", byBytes.exchange(kept.id)!!.request!!.request_id)
        }

    @Test
    fun anEnvelopeOffTheNetworkChannelLeavesTheExchangesAlone() =
        runBlocking {
            var now = 1_000L
            val live = LiveTraffic { now }
            live.connected("s1", hello(), null)
            live.received("s1", envelope(started("a")))
            val version = live.exchangesVersion
            now = 2_000

            live.received("s1", envelope(started("b"), channel = "logs"))

            assertEquals(version, live.exchangesVersion)
            assertEquals(
                2_000,
                live
                    .observeSessions()
                    .first()
                    .single()
                    .lastEnvelopeAtMs,
            )
        }

    @Test
    fun endpointsGroupTheLiveList() =
        runBlocking {
            val live = LiveTraffic()
            live.connected("s1", hello(), null)
            live.received("s1", envelope(started("a", url = "https://api.example.com/users?page=1"), 1_000))
            live.received("s1", envelope(response("a", 200, "abc"), 1_100))
            live.received("s1", envelope(started("b", url = "https://api.example.com/users?page=2"), 2_000))
            live.received("s1", envelope(response("b", 500), 2_300))
            live.received(
                "s1",
                envelope(
                    RequestStarted(
                        request_id = "c",
                        method = "CONNECT",
                        url = "tcp://db.local:5432",
                        capture_layer = CaptureLayer.CAPTURE_LAYER_CONNECTION,
                    ),
                ),
            )

            val groups = live.groupByEndpoint(ExchangeFilter())
            val users = groups.endpoints.first()
            assertEquals(Endpoint("GET", "api.example.com", "/users"), users.endpoint)
            assertEquals(2, users.count)
            assertEquals(1, users.errors)
            assertEquals(3, users.responseBytes)
            assertEquals(300, users.durationStats!!.max)
            assertEquals(
                ":5432",
                groups.endpoints
                    .last()
                    .endpoint.path,
            )
            assertEquals(3, groups.exchangesRead)
        }
}
