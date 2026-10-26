package appmonitor.desktop.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.traffic.COPY
import appmonitor.desktop.feature.traffic.COPY_NOTE
import appmonitor.desktop.feature.traffic.CURL
import appmonitor.desktop.feature.traffic.DETAIL_MATCHES
import appmonitor.desktop.feature.traffic.DETAIL_SEARCH
import appmonitor.desktop.feature.traffic.DetailPart
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.v1.Header
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DetailTabsUiTest {
    @Test
    fun `each detail tab shows its part of the request and copies it`() =
        withStorage { storage ->
            val live = LiveTraffic()
            live.record(
                envelope(
                    RequestStarted(
                        request_id = "r1",
                        method = "POST",
                        url = URL,
                        headers = listOf(Header("Content-Type", "application/json")),
                        body = "{\"name\":\"a\"}".encodeUtf8(),
                    ),
                    1_000,
                ),
                envelope(
                    ResponseReceived(
                        request_id = "r1",
                        status_code = 201,
                        headers = listOf(Header("Content-Type", "application/json"), Header("X-Request-Id", "42")),
                        body = "{\"id\":7}".encodeUtf8(),
                    ),
                    1_120,
                ),
            )
            val clipboard = FakeClipboard()
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent {
                    CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                        AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live)
                    }
                }

                fun copy(part: DetailPart): String {
                    clipboard.copied = null
                    onNodeWithTag(COPY + part.name).performClick()
                    waitUntil(timeoutMillis = TIMEOUT_MS) { clipboard.copied != null }
                    return clipboard.copied!!.text
                }

                awaitText(URL)
                onNodeWithText(URL).performClick()
                awaitText("Status 201 · Duration 120 ms", substring = true)
                assertEquals(URL, copy(DetailPart.Url))
                onNodeWithTag(COPY_NOTE).assertExists()
                screenshot("detail-summary.png")

                showTab("Headers")
                awaitText("X-Request-Id")
                onNodeWithText("42").assertExists()
                onNodeWithText("Response headers (2)").assertExists()
                assertEquals("Content-Type: application/json", copy(DetailPart.RequestHeaders))
                assertEquals("Content-Type: application/json\nX-Request-Id: 42", copy(DetailPart.ResponseHeaders))
                screenshot("detail-headers.png")

                showTab("Body")
                awaitText("{\n    \"id\": 7\n}")
                assertEquals("{\"name\":\"a\"}", copy(DetailPart.RequestBody))
                assertEquals("{\"id\":7}", copy(DetailPart.ResponseBody))
                screenshot("detail-body.png")

                showTab("Timing")
                awaitText("120 ms")
                val timing = copy(DetailPart.Timing)
                assertTrue(timing.startsWith("Started: ") && timing.endsWith("Duration: 120 ms"), timing)
                screenshot("detail-timing.png")

                showTab("cURL")
                awaitTag(CURL)
                val command = copy(DetailPart.Curl)
                assertTrue(command.startsWith("curl --globoff -X 'POST' '$URL'"), command)
                screenshot("detail-curl.png")

                onNodeWithTag(DETAIL_SEARCH).performTextInput("application")
                awaitText("2 matches")
                showTab("Headers · 2")
                screenshot("detail-search.png")
                onNodeWithTag(DETAIL_SEARCH).performTextReplacement("")
                onNodeWithTag(DETAIL_MATCHES).assertDoesNotExist()
                showTab("Headers")
            }
        }
}
