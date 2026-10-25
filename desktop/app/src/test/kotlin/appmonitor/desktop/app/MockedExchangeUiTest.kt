package appmonitor.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.traffic.MOCKED
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.desktop.storage.MockSource
import appmonitor.desktop.storage.MockWrite
import appmonitor.v1.CaptureLayer
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs

// M9.5: an exchange a rule answered on the device, marked in the list and named in the detail (ADR-0110 E.1).
@OptIn(ExperimentalTestApi::class)
class MockedExchangeUiTest {
    @Test
    fun `a mocked exchange is marked in the list and names its rule in the detail`() =
        withStorage { storage ->
            val live = LiveTraffic()
            val rule = MockRule(host = "api.example.com", path = "/v1/items", response = MockResponse(status_code = 503))
            val id = assertIs<MockWrite.Saved>(runBlocking { storage.createMock(HELLO.app_id, "items down", rule, MockSource.WINDOW) }).id
            live.record(
                envelope(call("m", "https://api.example.com/v1/items", id), 1_000),
                envelope(ResponseReceived(request_id = "m", status_code = 503), 1_005),
                envelope(call("n", "https://api.example.com/v1/orders", null), 2_000),
                envelope(ResponseReceived(request_id = "n", status_code = 200), 2_010),
                envelope(call("d", "https://api.example.com/v1/gone", 9_999), 3_000),
                envelope(ResponseReceived(request_id = "d", status_code = 200), 3_001),
            )
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", live = live) }

                awaitText("https://api.example.com/v1/items")
                onNodeWithTag(MOCKED + "m", useUnmergedTree = true).assertExists()
                onNodeWithTag(MOCKED + "n", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithText("https://api.example.com/v1/items").performClick()
                awaitText("Status 503 · Duration 5 ms · Adapter layer · Mocked by rule \"items down\"")
                screenshot("mocked.png")
                onNodeWithText("https://api.example.com/v1/gone").performClick()
                awaitText("Status 200 · Duration 1 ms · Adapter layer · Mocked by deleted rule 9999")
                onNodeWithText("https://api.example.com/v1/orders").performClick()
                awaitText("Status 200 · Duration 10 ms · Adapter layer")
            }
        }

    private fun call(
        requestId: String,
        url: String,
        rule: Long?,
    ) = RequestStarted(
        request_id = requestId,
        method = "GET",
        url = url,
        capture_layer = CaptureLayer.CAPTURE_LAYER_ADAPTER,
        mock_rule_id = rule,
    )
}
