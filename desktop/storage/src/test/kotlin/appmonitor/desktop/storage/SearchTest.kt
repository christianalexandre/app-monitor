package appmonitor.desktop.storage

import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class SearchTest {
    @Test
    fun eachFilterAndTheirCombination() =
        withSearchData { storage, second ->
            fun ids(filter: ExchangeFilter) = runBlocking { storage.searchExchanges(filter).map { it.requestId } }

            assertEquals(ALL, ids(ExchangeFilter()), "no filter is every exchange, in arrival order")
            assertEquals(listOf("login", "cart", "pay"), ids(ExchangeFilter(text = "EXAMPLE.com/")), "URL, any case")
            assertEquals(listOf("cdn"), ids(ExchangeFilter(text = "cdn.example")), "host")
            assertEquals(listOf("percent"), ids(ExchangeFilter(text = "100%")), "% is literal")
            assertEquals(listOf("under"), ids(ExchangeFilter(text = "a_b")), "_ is literal")
            assertEquals(ALL, ids(ExchangeFilter(text = "")), "empty text matches all")
            assertEquals(listOf("login", "pay"), ids(ExchangeFilter(method = "POST")))
            assertEquals(listOf("login", "cdn", "percent"), ids(ExchangeFilter(statusClass = StatusClass.SUCCESS)))
            assertEquals(listOf("cart"), ids(ExchangeFilter(statusClass = StatusClass.CLIENT_ERROR)))
            assertEquals(listOf("pay"), ids(ExchangeFilter(statusClass = StatusClass.SERVER_ERROR)))
            assertEquals(listOf("timeout"), ids(ExchangeFilter(failedOnly = true)))
            assertEquals(listOf("cart", "pay", "under"), ids(ExchangeFilter(deviceId = "iphone")))
            assertEquals(listOf("percent", "under"), ids(ExchangeFilter(appId = "other")))
            assertEquals(listOf("cart", "pay", "under"), ids(ExchangeFilter(runId = second)))
            val session = runBlocking { storage.sessions(storage.runId) }.first()
            assertEquals(listOf("login", "cdn", "timeout"), ids(ExchangeFilter(sessionId = session.id)))
            assertEquals(emptyList(), ids(ExchangeFilter(deviceId = "unknown")))

            assertEquals(
                listOf("pay"),
                ids(
                    ExchangeFilter(
                        text = "example",
                        method = "POST",
                        statusClass = StatusClass.SERVER_ERROR,
                        deviceId = "iphone",
                        appId = "dev.appmonitor.sample",
                        runId = second,
                    ),
                ),
                "filters are combined with AND",
            )
            assertEquals(emptyList(), ids(ExchangeFilter(method = "POST", runId = storage.runId, statusClass = StatusClass.SERVER_ERROR)))
        }

    @Test
    fun pagesByKeyUnderAFilter() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                storage.connected("s1", hello(), null)
                repeat(500) {
                    storage.received(
                        "s1",
                        envelope(
                            request(
                                "r$it",
                                if (it % 2 ==
                                    0
                                ) {
                                    "GET"
                                } else {
                                    "POST"
                                },
                                "https://a.example/$it",
                            ),
                        ),
                    )
                }
                storage.flush()
                val seen = mutableListOf<StoredExchange>()
                while (true) {
                    val page = storage.searchExchanges(ExchangeFilter(method = "POST"), afterId = seen.lastOrNull()?.id ?: 0)
                    if (page.isEmpty()) break
                    assertTrue(page.size <= Storage.PAGE_SIZE)
                    seen += page
                }
                assertEquals((1 until 500 step 2).map { "r$it" }, seen.map { it.requestId })
                storage.close(5.seconds)
            }
        }

    @Test
    fun observedSearchFollowsCommits() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                withTimeout(5.seconds) {
                    storage.connected("s1", hello(), null)
                    storage.received("s1", envelope(request("r1", "GET", "https://a.example/1")))
                    storage.flush()
                    val failed = storage.observeExchanges(ExchangeFilter(failedOnly = true))
                    assertEquals(emptyList(), failed.first())

                    storage.received("s1", envelope(RequestFailed(request_id = "r1", error = "timeout")))
                    assertEquals(listOf("r1"), failed.first { it.isNotEmpty() }.map { it.requestId })
                }
                storage.close(5.seconds)
            }
        }

    // ADR-0087: each indexed filter is answered through its index, not by reading every exchange.
    @Test
    fun indexedFiltersUseTheirIndex() =
        withSearchData { storage, second ->
            val session = runBlocking { storage.sessions(storage.runId) }.first()
            val cases =
                mapOf(
                    ExchangeFilter(method = "POST") to "exchanges_method",
                    ExchangeFilter(statusClass = StatusClass.CLIENT_ERROR) to "exchanges_status_code",
                    ExchangeFilter(sessionId = session.id) to "exchanges_session_ref_id",
                    ExchangeFilter(runId = second) to "exchanges_session_ref_id",
                    ExchangeFilter(deviceId = "iphone") to "exchanges_session_ref_id",
                    ExchangeFilter(appId = "other") to "exchanges_session_ref_id",
                )
            cases.forEach { (filter, index) ->
                val plan = storage.searchPlan(filter)
                assertTrue(plan.any { "SEARCH exchanges USING INDEX $index" in it }, "$filter: $plan")
            }
        }

    // DEBT-0063: text and "failed only" walk the exchanges in id order; this measures the worst case, no match.
    @Test
    fun unindexedFiltersOverManyExchangesInBoundedTime() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                storage.connected("s1", hello(), null)
                repeat(MANY) { storage.received("s1", envelope(request("r$it", "GET", "https://api.example.com/items/$it?page=2"))) }
                storage.flush()
                val text = measureTime { assertEquals(emptyList(), storage.searchExchanges(ExchangeFilter(text = "no-such-path"))) }
                val failed = measureTime { assertEquals(emptyList(), storage.searchExchanges(ExchangeFilter(failedOnly = true))) }
                println("Search over $MANY exchanges, no match: text $text, failed only $failed")
                assertTrue(text < 2.seconds && failed < 2.seconds, "text $text, failed $failed")
                storage.close(5.seconds)
            }
        }

    /** Two runs: the first with a Pixel, the second with an iPhone, each on the sample app and `other`. */
    private fun withSearchData(block: (Storage, Long) -> Unit) =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                storage.connected("s1", hello("pixel"), null)
                storage.received("s1", envelope(request("login", "POST", "https://api.example.com/login")))
                storage.received("s1", envelope(ResponseReceived(request_id = "login", status_code = 201)))
                storage.received("s1", envelope(request("cdn", "GET", "https://cdn.example.org/logo.png")))
                storage.received("s1", envelope(ResponseReceived(request_id = "cdn", status_code = 200)))
                storage.received("s1", envelope(request("timeout", "GET", "https://slow.example.net/feed")))
                storage.received("s1", envelope(RequestFailed(request_id = "timeout", error = "timeout")))
                storage.connected("s2", hello("pixel").copy(app_id = "other"), null)
                storage.received("s2", envelope(request("percent", "GET", "https://b.example.net/discount?off=100%25")))
                storage.received("s2", envelope(ResponseReceived(request_id = "percent", status_code = 204)))
                storage.disconnected("s1")
                storage.disconnected("s2")
                val second = storage.newRun()
                storage.connected("s3", hello("iphone"), null)
                storage.received("s3", envelope(request("cart", "GET", "https://shop.example.com/cart")))
                storage.received("s3", envelope(ResponseReceived(request_id = "cart", status_code = 404)))
                storage.received("s3", envelope(request("pay", "POST", "https://shop.example.com/pay")))
                storage.received("s3", envelope(ResponseReceived(request_id = "pay", status_code = 503)))
                storage.connected("s4", hello("iphone").copy(app_id = "other"), null)
                storage.received("s4", envelope(request("under", "GET", "https://c.example.net/a_b")))
                storage.flush()
                block(storage, second)
                storage.close(5.seconds)
            }
        }

    private fun request(
        id: String,
        method: String,
        url: String,
    ) = RequestStarted(request_id = id, method = method, url = url)

    private companion object {
        val ALL = listOf("login", "cdn", "timeout", "percent", "cart", "pay", "under")
        const val MANY = 100_000
    }
}
