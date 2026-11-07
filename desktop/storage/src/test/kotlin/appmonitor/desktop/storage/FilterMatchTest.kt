package appmonitor.desktop.storage

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.RequestFailed
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

// DEBT-0104's guard: a filter or an event the store and the live list read differently fails here.
class FilterMatchTest {
    private suspend fun TrafficSink.traffic() {
        connected("s1", hello("pixel"), null)
        received("s1", envelope(started("login", "https://API.example.com/login").copy(method = "POST"), atMs = 10))
        received("s1", envelope(ResponseReceived(request_id = "login", status_code = 201), atMs = 11))
        received("s1", envelope(started("gone", "https://cdn.example.org/100%25_off"), atMs = 12))
        received("s1", envelope(ResponseReceived(request_id = "gone", status_code = 404), atMs = 13))
        received("s1", envelope(started("timeout", "https://slow.example.net/feed"), atMs = 14))
        received("s1", envelope(RequestFailed(request_id = "timeout", error = "timeout"), atMs = 15))
        connected("s2", hello("iphone").copy(app_id = "other"), null)
        received("s2", envelope(started("bad-url", "not a url"), atMs = 20))
        received("s2", envelope(ResponseReceived(request_id = "no-status", status_code = 0), atMs = 21))
        val trailers = listOf(Header("grpc-status", "0"), Header("Grpc-Status", " 13 "))
        received("s2", envelope(ResponseReceived(request_id = "grpc", status_code = 200, headers = trailers), atMs = 22))
        received("s2", envelope(WebSocketFrame(request_id = "frame-first", payload = "hi".encodeUtf8(), payload_size = 2), atMs = 23))
        received("s2", Envelope(Protocol.CHANNEL_NETWORK, "NoSuchEvent", "x".encodeUtf8(), 24))
        received("s2", Envelope(Protocol.CHANNEL_NETWORK, "RequestStarted", "ÿ".encodeUtf8(), 25))
    }

    private fun both(block: suspend (store: Storage, live: LiveTraffic) -> Unit) =
        withDataDir { dir ->
            val store = Storage.open(dir)
            val live = LiveTraffic()
            runBlocking {
                store.traffic()
                live.traffic()
                store.flush()
                block(store, live)
                store.close(5.seconds)
            }
        }

    @Test
    fun oneTableOfFiltersOnBoth() =
        both { store, live ->
            val storeSessions = store.sessions(store.runId).associate { it.serverSessionId to it.id }
            val liveSessions = live.sessions().associate { it.serverSessionId to it.id }
            val all = listOf("login", "gone", "timeout", "bad-url", "no-status", "grpc", "frame-first")
            val cases =
                listOf<Triple<ExchangeFilter, ExchangeFilter, List<String>>>(
                    same(ExchangeFilter(), all),
                    same(ExchangeFilter(text = ""), all),
                    same(ExchangeFilter(text = "api.EXAMPLE"), listOf("login")),
                    same(ExchangeFilter(text = "100%"), listOf("gone")),
                    same(ExchangeFilter(text = "%_off"), emptyList()),
                    same(ExchangeFilter(text = "_off"), listOf("gone")),
                    same(ExchangeFilter(text = "not a"), listOf("bad-url")),
                    same(ExchangeFilter(method = "POST"), listOf("login")),
                    same(ExchangeFilter(statusClasses = setOf(StatusClass.SUCCESS)), listOf("login", "grpc")),
                    same(
                        ExchangeFilter(statusClasses = setOf(StatusClass.CLIENT_ERROR, StatusClass.SUCCESS)),
                        listOf("login", "gone", "grpc"),
                    ),
                    same(ExchangeFilter(hosts = setOf("cdn.example.org", "slow.example.net")), listOf("gone", "timeout")),
                    same(ExchangeFilter(failedOnly = true), listOf("timeout")),
                    same(ExchangeFilter(noHost = true), emptyList()),
                    same(ExchangeFilter(deviceId = "iphone"), listOf("bad-url", "no-status", "grpc", "frame-first")),
                    same(ExchangeFilter(appId = "other", statusClasses = setOf(StatusClass.SUCCESS)), listOf("grpc")),
                    same(ExchangeFilter(deviceId = "nobody"), emptyList()),
                    same(ExchangeFilter(deviceId = "pixel", failedOnly = true, text = "feed"), listOf("timeout")),
                    Triple(
                        ExchangeFilter(sessionId = storeSessions.getValue("s1")),
                        ExchangeFilter(sessionId = liveSessions.getValue("s1")),
                        listOf("login", "gone", "timeout"),
                    ),
                )
            cases.forEach { (stored, inMemory, expected) ->
                assertEquals(expected, store.searchExchanges(stored).map { it.requestId }, "store: $stored")
                assertEquals(expected, live.searchExchanges(inMemory).map { it.requestId }, "live: $inMemory")
            }
        }

    @Test
    fun oneDecodingOnBoth() =
        both { store, live ->
            fun List<StoredExchange>.rows() = map { it.copy(id = 0, sessionId = 0) }
            val stored = store.searchExchanges(ExchangeFilter()).rows()
            assertEquals(stored, live.searchExchanges(ExchangeFilter()).rows())
            val byId = stored.associateBy { it.requestId }
            assertEquals(null, byId.getValue("no-status").statusCode, "status 0 is no status")
            assertEquals(13, byId.getValue("grpc").grpcStatus, "the last grpc-status, trimmed")
            assertEquals(null, byId.getValue("bad-url").host, "a URL that does not parse has no host")
            assertEquals(1, byId.getValue("frame-first").frameCount, "an event for an unknown request creates it")
        }

    private fun same(
        filter: ExchangeFilter,
        expected: List<String>,
    ) = Triple(filter, filter, expected)
}
