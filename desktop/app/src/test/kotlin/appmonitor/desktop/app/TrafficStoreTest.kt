package appmonitor.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.app.ui.REQUEST_LIST
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
    fun `a 10k-request session is stored in bounded time`() =
        withStorage { storage ->
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

            val elapsed = measureTime { storage.receive(traffic) }
            val stored = runBlocking { storage.allExchanges() }

            println("TrafficStoreTest: $REQUESTS requests stored in $elapsed, queue high water ${storage.queueHighWaterBytes} B")
            assertEquals(REQUESTS, stored.size)
            assertTrue(stored.all { it.statusCode == 201 }, "every response stored")
            assertTrue(elapsed < BOUND, "storing $REQUESTS requests took $elapsed")
            assertEquals(0, storage.queueFullWaits, "the collector never waited for the disk")
        }

    @Test
    fun `a reopened store shows the earlier traffic`() =
        withDataDir { dir ->
            Storage.open(dir).run {
                receive()
                runBlocking { close(5.seconds) }
            }
            val reopened = Storage.open(dir)
            try {
                assertEquals(2, runBlocking { reopened.runs() }.size, "the launch opened a run of its own")
                runDesktopComposeUiTest(width = 1280, height = 800) {
                    setContent { AppMonitorApp(reopened, "Listening on port 47474") }

                    awaitText("Pixel 9 (emulator)")
                    onNodeWithText("Android 16 · disconnected").assertExists()
                    awaitText(URL)
                    onNodeWithText(URL).performClick()
                    awaitText("Content-Type: application/json")
                    screenshot("reopen.png")
                }
            } finally {
                runBlocking { reopened.close(5.seconds) }
            }
        }

    @Test
    fun `the request list reads the next page at its end`() =
        withStorage { storage ->
            val requests =
                (0 until 450).map {
                    envelope(
                        RequestStarted(request_id = "r$it", method = "GET", url = "https://a.example/$it"),
                    )
                }
            storage.record(*requests.toTypedArray())

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening") }

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
