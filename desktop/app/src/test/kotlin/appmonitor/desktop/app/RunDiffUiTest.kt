package appmonitor.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.app.ui.COMPARE_RUNS
import appmonitor.desktop.app.ui.DIFF_BASE
import appmonitor.desktop.app.ui.DIFF_RUN
import appmonitor.desktop.app.ui.DiffRow
import appmonitor.desktop.app.ui.RUNS
import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.diffEndpoints
import appmonitor.desktop.storage.groupByEndpoint
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class RunDiffUiTest {
    private class Runs(
        val base: Long,
        val run: Long,
    )

    /** `DiffRunsTest`'s two runs: `/users` gains an error, `/orders` p95 100 → 130 ms, `/gone`, `/added`, `POST /users`. */
    private fun Storage.seed(): Runs {
        var at = 0L

        fun exchange(
            id: String,
            path: String,
            status: Int,
            durationMs: Long,
            method: String = "GET",
        ) = listOf(
            envelope(RequestStarted(request_id = id, method = method, url = "https://api.example.com$path"), (at + 1_000).also { at = it }),
            envelope(ResponseReceived(request_id = id, status_code = status), at + durationMs),
        )
        record(
            *listOf(
                exchange("a1", "/users", 200, 100),
                exchange("a2", "/users", 200, 100),
                exchange("a3", "/orders", 200, 100),
                exchange("a4", "/stable", 200, 100),
                exchange("a5", "/small", 200, 100),
                exchange("a6", "/tiny", 200, 5),
                exchange("a7", "/gone", 200, 100),
            ).flatten().toTypedArray(),
        )
        val base = runId
        val run = runBlocking { newRun() }
        record(
            *listOf(
                exchange("b1", "/users", 200, 100),
                exchange("b2", "/users", 500, 100),
                exchange("b3", "/orders", 200, 130),
                exchange("b4", "/stable", 200, 100),
                exchange("b5", "/small", 200, 115),
                exchange("b6", "/tiny", 200, 9),
                exchange("b7", "/added", 200, 100),
                exchange("b8", "/users", 201, 100, method = "POST"),
            ).flatten().toTypedArray(),
            sessionId = "s2",
        )
        return Runs(base, run)
    }

    @Test
    fun `the window's model shows diff_runs' comparison`() =
        withStorage { storage ->
            val runs = storage.seed()
            val diff =
                runBlocking {
                    diffEndpoints(
                        storage.groupByEndpoint(ExchangeFilter(runId = runs.base)),
                        storage.groupByEndpoint(ExchangeFilter(runId = runs.run)),
                    )
                }
            assertEquals(3, diff.unchanged, "stable, small under 20 %, tiny under 10 ms")
            assertEquals(
                listOf(
                    DiffRow("GET api.example.com/users", "2 → 2", "0.0 % → 50.0 %", "100 ms → 100 ms", "100 ms → 100 ms"),
                    DiffRow("GET api.example.com/orders", "1 → 1", "0.0 % → 0.0 %", "100 ms → 130 ms", "100 ms → 130 ms"),
                ),
                diff.changed.map(DiffRow::of),
            )
            assertEquals(
                listOf(
                    DiffRow("GET api.example.com/added", "1", "0.0 %", "100 ms", "100 ms"),
                    DiffRow("POST api.example.com/users", "1", "0.0 %", "100 ms", "100 ms"),
                ),
                diff.added.map(DiffRow::of),
            )
            assertEquals(listOf(DiffRow("GET api.example.com/gone", "1", "0.0 %", "100 ms", "100 ms")), diff.gone.map(DiffRow::of))
        }

    @Test
    fun `two runs picked from the run list are compared per endpoint`() =
        withStorage { storage ->
            val runs = storage.seed()
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening") }

                onNodeWithTag(RUNS).performClick()
                onNodeWithTag(COMPARE_RUNS).performClick()
                awaitText("2 added · 1 gone · 2 changed · 3 unchanged")
                onNodeWithText("0.0 % → 50.0 %").assertExists()
                onNodeWithText("GET api.example.com/gone").assertExists()
                screenshot("diff.png")

                onNodeWithTag(DIFF_RUN + runs.base).performClick()
                awaitText("0 added · 0 gone · 0 changed · 6 unchanged")
                onNodeWithTag(DIFF_BASE + runs.run).performClick()
                awaitText("1 added · 2 gone · 2 changed · 3 unchanged")
            }
        }
}
