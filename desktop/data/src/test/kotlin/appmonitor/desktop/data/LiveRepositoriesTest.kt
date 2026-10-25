package appmonitor.desktop.data

import appmonitor.desktop.domain.Endpoint
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.MockSource
import appmonitor.desktop.domain.MockWrite
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.flow.first
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import appmonitor.desktop.storage.ExchangeFilter as StoredExchangeFilter

class LiveRepositoriesTest {
    @Test
    fun `live traffic of two devices is one list and the store gets no row of it`() =
        withStorage { storage ->
            val live = LiveTraffic()
            val traffic = storage.trafficRepository(live)
            live.connected("s1", HELLO, null)
            live.connected("s2", HELLO.copy(device_id = "d2"), null)
            live.received("s1", envelope(RequestStarted("a", "GET", "https://api.test/a")))
            live.received("s2", envelope(RequestStarted("b", "GET", "https://api.test/b")))

            assertEquals(listOf("a", "b"), traffic.observeExchanges(ExchangeFilter()).first().map { it.requestId })
            assertEquals(listOf("d1", "d2"), traffic.observeSessions(null).first().map { it.hello.device_id })
            storage.flush()
            assertEquals(emptyList(), storage.searchExchanges(StoredExchangeFilter()))
            assertEquals(emptyList(), storage.sessions(storage.runId))

            live.clear()
            assertEquals(emptyList(), traffic.observeExchanges(ExchangeFilter()).first())
        }

    @Test
    fun `a run, a stored session or a stored id reads the store, anything else the live list`() =
        withStorage { storage ->
            storage.record(envelope(RequestStarted("stored", "GET", "https://api.test/stored")))
            val live = LiveTraffic()
            live.connected("s1", HELLO, null)
            live.received("s1", envelope(RequestStarted("live", "GET", "https://api.test/live")))
            val traffic = storage.trafficRepository(live)
            val queries = storage.trafficQueryRepository(live)
            val run = storage.runId
            val storedSession = storage.sessions(run).single().id
            val liveSession = live.sessions().single().id

            assertEquals(listOf("stored"), traffic.observeExchanges(ExchangeFilter(runId = run)).first().map { it.requestId })
            assertEquals(listOf("stored"), traffic.observeExchanges(ExchangeFilter(sessionId = storedSession)).first().map { it.requestId })
            assertEquals(listOf("live"), traffic.observeExchanges(ExchangeFilter(sessionId = liveSession)).first().map { it.requestId })
            assertEquals(listOf(storedSession), traffic.observeSessions(run).first().map { it.id })
            assertEquals(listOf("live"), queries.search(ExchangeFilter(), 0, 10).map { it.requestId })
            assertEquals(listOf("stored"), queries.search(ExchangeFilter(runId = run), 0, 10).map { it.requestId })

            val storedId = storage.searchExchanges(StoredExchangeFilter()).single().id
            val liveId = live.searchExchanges(StoredExchangeFilter()).single().id
            assertEquals("stored", traffic.exchange(storedId)!!.requestId)
            assertEquals("live", traffic.exchange(liveId)!!.requestId)
            assertEquals("live", queries.detail(liveId)!!.exchange.requestId)
            assertEquals(liveSession, queries.session(liveSession)!!.id)
            assertEquals(storedSession, queries.session(storedSession)!!.id)
            assertEquals(listOf(run), queries.runs().map { it.id })
        }

    @Test
    fun `a live exchange reads its frames, its original and its rule's name from the store`() =
        withStorage { storage ->
            val rule = MockRule(host = "api.test", path = "/users", response = MockResponse(status_code = 503))
            val ruleId = (storage.mockRepository().create("app", "down", rule, MockSource.WINDOW) as MockWrite.Saved).id
            val live = LiveTraffic()
            live.connected("s1", HELLO, null)
            live.received("s1", envelope(RequestStarted("r1", "GET", "https://api.test/users", mock_rule_id = ruleId)))
            live.received("s1", envelope(RequestStarted("r2", "GET", "https://api.test/users", replay_of = "r1")))
            live.received("s1", envelope(RequestStarted("ws", "GET", "wss://api.test/feed")))
            live.received("s1", envelope(WebSocketFrame(request_id = "ws", payload = "hi".encodeUtf8(), payload_size = 2)))
            val traffic = storage.trafficRepository(live)
            val queries = storage.trafficQueryRepository(live)
            val (r1, r2, ws) = queries.search(ExchangeFilter(), 0, 10)

            assertEquals("down", traffic.exchange(r1.id)!!.mockRuleName)
            assertEquals("down", queries.detail(r1.id)!!.mockRuleName)
            assertEquals(r1.id, traffic.exchange(r2.id)!!.replayOfId)
            assertEquals(mapOf(r2.id to r1.id), queries.replayOriginals(listOf(r1, r2)))
            assertEquals(listOf("hi".encodeUtf8()), traffic.exchange(ws.id)!!.frames.map { it.payload.bytes })
            assertEquals(listOf("hi".encodeUtf8()), queries.frames(ws.id, 0, 10).map { it.payload.bytes })

            storage.mockRepository().delete(ruleId)
            storage.flush()
            assertNull(traffic.exchange(r1.id)!!.mockRuleName)
        }

    @Test
    fun `endpoints group the live list unless a run is named`() =
        withStorage { storage ->
            storage.record(envelope(RequestStarted("stored", "POST", "https://api.test/stored")))
            val live = LiveTraffic()
            live.connected("s1", HELLO, null)
            live.received("s1", envelope(RequestStarted("a", "GET", "https://api.test/users")))
            live.received("s1", envelope(ResponseReceived("a", 200)))
            val benchmarks = storage.benchmarkRepository(live)

            assertEquals(
                listOf(Endpoint("GET", "api.test", "/users")),
                benchmarks.endpoints(ExchangeFilter()).endpoints.map { it.endpoint },
            )
            assertEquals(
                listOf(Endpoint("POST", "api.test", "/stored")),
                benchmarks.endpoints(ExchangeFilter(runId = storage.runId)).endpoints.map { it.endpoint },
            )
            assertTrue(benchmarks.diff(ExchangeFilter(), storage.runId, storage.runId).added.isEmpty())
        }
}
