package appmonitor.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.data.benchmarkRepository
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.feature.insights.BENCHMARK_COLUMN
import appmonitor.desktop.feature.insights.BenchmarkColumn
import appmonitor.desktop.feature.insights.BenchmarkRow
import appmonitor.desktop.feature.insights.sortBenchmarks
import appmonitor.desktop.feature.traffic.BENCHMARKS
import appmonitor.desktop.feature.traffic.SEARCH
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.v1.Envelope
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BenchmarksUiTest {
    /** `EndpointStatsTest.knownP99`'s run (200 searches of 1 to 200 ms, every 20th a 500), plus two endpoints. */
    private fun seed(): Array<Envelope> {
        val searches =
            (1..200).flatMap { i ->
                listOf(
                    envelope(RequestStarted(request_id = "r$i", method = "GET", url = "https://api.example.com/search?q=$i"), i * 1_000L),
                    envelope(ResponseReceived(request_id = "r$i", status_code = if (i % 20 == 0) 500 else 200), i * 1_000L + i),
                )
            }
        val others =
            listOf(
                envelope(
                    RequestStarted(
                        request_id = "o1",
                        method = "POST",
                        url = "https://api.example.com/orders",
                        body = "1234567".encodeUtf8(),
                    ),
                ),
                envelope(ResponseReceived(request_id = "o1", status_code = 201), 1_400),
                envelope(RequestStarted(request_id = "o2", method = "POST", url = "https://api.example.com/orders")),
                envelope(RequestFailed(request_id = "o2", error = "timeout"), 3_000),
                envelope(RequestStarted(request_id = "f1", method = "GET", url = "https://cdn.example.org/feed")),
            )
        return (searches + others).toTypedArray()
    }

    @Test
    fun `the window's model shows endpoint_stats' numbers, p99 included, sortable`() =
        withStorage { storage ->
            storage.record(*seed())
            val groups = runBlocking { storage.benchmarkRepository().endpoints(ExchangeFilter(runId = storage.runId)) }
            assertEquals(203, groups.exchangesRead)
            val (search, orders, feed) = groups.endpoints
            assertEquals(
                BenchmarkRow("GET api.example.com/search", "200", "2xx 190 · 5xx 10", "5.0 %", "100 ms", "190 ms", "198 ms", "0 B", "0 B"),
                BenchmarkRow.of(search),
            )
            assertEquals(
                BenchmarkRow("POST api.example.com/orders", "2", "2xx 1 · failed 1", "50.0 %", "400 ms", "2.00 s", "2.00 s", "7 B", "0 B"),
                BenchmarkRow.of(orders),
            )
            assertEquals(
                BenchmarkRow("GET cdn.example.org/feed", "1", "—", "0.0 %", "—", "—", "—", "0 B", "0 B"),
                BenchmarkRow.of(feed),
            )
            assertEquals(listOf(orders, search, feed), sortBenchmarks(groups.endpoints, BenchmarkColumn.P99, descending = true))
            assertEquals(listOf(search, orders, feed), sortBenchmarks(groups.endpoints, BenchmarkColumn.P99, descending = false))
            assertEquals(listOf(orders, search, feed), sortBenchmarks(groups.endpoints, BenchmarkColumn.ERROR_RATE, descending = true))
            assertEquals(listOf(orders, search, feed), sortBenchmarks(groups.endpoints, BenchmarkColumn.ENDPOINT, descending = false))
        }

    @Test
    fun `benchmarks open from the request list, sort by a column and follow the filter`() =
        withStorage { storage ->
            val live = LiveTraffic()
            live.record(*seed())
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", live = live) }

                awaitText("https://api.example.com/search?q=1")
                onNodeWithTag(BENCHMARKS).performClick()
                awaitText("203 requests read")
                onNodeWithText("198 ms").assertExists()
                onNodeWithText("2xx 190 · 5xx 10").assertExists()
                onNodeWithTag(BENCHMARK_COLUMN + BenchmarkColumn.P99.name).performClick()
                awaitText("p99 ▼")
                screenshot("benchmarks.png")
                onNodeWithText("Close").performClick()

                onNodeWithTag(SEARCH).performTextInput("orders")
                onNodeWithTag(BENCHMARKS).performClick()
                awaitText("2 requests read")
                onNodeWithText("Every device in the live list", substring = true).assertExists()
                onNodeWithText("under the request filter", substring = true).assertExists()
            }
        }
}
