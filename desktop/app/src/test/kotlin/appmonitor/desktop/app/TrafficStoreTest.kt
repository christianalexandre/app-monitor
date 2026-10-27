package appmonitor.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.traffic.REQUEST_LIST
import appmonitor.desktop.feature.traffic.RUNS
import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.desktop.storage.Storage
import appmonitor.tools.fakeclient.Fixtures
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

@OptIn(ExperimentalTestApi::class)
class TrafficStoreTest {
    @Test
    fun `a 10k-request session is listed in bounded time and not stored`() =
        withStorage { storage ->
            val live = LiveTraffic()
            val (started, responded) = Fixtures.traffic
            val request = RequestStarted.ADAPTER.decode(started.payload)
            val response = ResponseReceived.ADAPTER.decode(responded.payload)
            val traffic =
                (0 until REQUESTS).flatMap { i ->
                    listOf(
                        started.copy(payload = request.copy(request_id = "r$i").encodeByteString()),
                        responded.copy(payload = response.copy(request_id = "r$i").encodeByteString()),
                    )
                }

            val elapsed = measureTime { live.receive(storage, traffic) }
            val listed = live.searchExchanges(ExchangeFilter(), limit = REQUESTS + 1L)

            println("TrafficStoreTest: $REQUESTS requests listed in $elapsed")
            assertEquals(REQUESTS, listed.size)
            assertTrue(listed.all { it.statusCode == 201 }, "every response listed")
            assertTrue(elapsed < BOUND, "listing $REQUESTS requests took $elapsed")
            assertEquals(emptyList(), runBlocking { storage.allExchanges() }, "nothing is recording, so nothing is stored")
        }

    @Test
    fun `a reopened store shows its stored runs, not the live list`() =
        withDataDir { dir ->
            Storage.open(dir).run {
                record(*Fixtures.traffic.toTypedArray())
                runBlocking { close(5.seconds) }
            }
            val reopened = Storage.open(dir)
            try {
                assertEquals(2, runBlocking { reopened.runs() }.size, "the launch opened a run of its own")
                runDesktopComposeUiTest(width = 1280, height = 800) {
                    setContent { AppMonitorApp(reopened, "Listening on port 47474", appearance = TestAppearance) }

                    // The live list starts empty; the stored run, ended, is one click away (ADR-0140).
                    awaitText("No requests yet")
                    onNodeWithTag(RUNS).performClick()
                    onNodeWithText(" – ", substring = true).performClick()
                    awaitText("Pixel")
                    onNodeWithText("Android 16 · disconnected").assertExists()
                    awaitText(URL)
                    onNodeWithText(URL).performClick()
                    showTab("Headers")
                    awaitText("application/json")
                    screenshot("reopen.png")
                }
            } finally {
                runBlocking { reopened.close(5.seconds) }
            }
        }

    @Test
    fun `the request list reads the next page at its end`() =
        withStorage { storage ->
            val live = LiveTraffic()
            val requests =
                (0 until 450).map {
                    envelope(
                        RequestStarted(request_id = "r$it", method = "GET", url = "https://a.example/$it"),
                    )
                }
            live.record(*requests.toTypedArray())

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }

                awaitText("https://a.example/0")
                // An index past the rows read so far is refused, so each one waits for the page before it.
                listOf(199, 399, 449).forEach { index ->
                    waitUntil(
                        timeoutMillis = TIMEOUT_MS,
                    ) { runCatching { onNodeWithTag(REQUEST_LIST).performScrollToIndex(index) }.isSuccess }
                }
                awaitText("https://a.example/449")
            }
        }

    private companion object {
        const val REQUESTS = 10_000
        val BOUND = 10.seconds
    }
}
