package appmonitor.desktop.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.designsystem.SEARCH_CLEAR
import appmonitor.desktop.feature.traffic.COPY
import appmonitor.desktop.feature.traffic.COPY_CURL_ACTION
import appmonitor.desktop.feature.traffic.COPY_NOTE
import appmonitor.desktop.feature.traffic.CURL
import appmonitor.desktop.feature.traffic.DETAIL_MATCHES
import appmonitor.desktop.feature.traffic.DETAIL_META
import appmonitor.desktop.feature.traffic.DETAIL_SEARCH
import appmonitor.desktop.feature.traffic.DETAIL_SEARCH_OPEN
import appmonitor.desktop.feature.traffic.DetailPart
import appmonitor.desktop.feature.traffic.MORE_ACTIONS
import appmonitor.desktop.feature.traffic.NEXT_MATCH
import appmonitor.desktop.feature.traffic.PREVIOUS_MATCH
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.v1.Header
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DetailTabsUiTest {
    @Test
    fun `a gzip body opens in the detail, says so, and is copied opened, a cut one too`() =
        withStorage { storage ->
            val document = """{"items":[${List(3_000) { "\"item-$it\"" }.joinToString(",")}]}"""
            val whole = gzip(document)
            val encoded = listOf(Header("Content-Type", "application/json"), Header("Content-Encoding", "gzip"))
            val live = LiveTraffic()
            live.record(
                envelope(
                    RequestStarted(
                        request_id = "r1",
                        method = "POST",
                        url = URL,
                        headers = encoded,
                        body = gzip("{\"sent\":true}"),
                    ),
                    1_000,
                ),
                envelope(
                    ResponseReceived(
                        request_id = "r1",
                        status_code = 200,
                        headers = encoded,
                        body = whole.substring(0, whole.size / 2),
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

                awaitText(URL)
                onNodeWithText(URL).performClick()
                showTab("Body")
                awaitText("{\n    \"sent\": true\n}")
                awaitText("Opened from gzip: ", substring = true)
                awaitText("Gzip stream cut: the ", substring = true)
                awaitText("{\"items\":[\"item-0\",\"item-1\"", substring = true)
                screenshot("detail-gzip.png")

                onNodeWithTag(COPY + DetailPart.RequestBody.name).performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { clipboard.copied != null }
                assertEquals("{\"sent\":true}", clipboard.copied!!.text)
                clipboard.copied = null
                onNodeWithTag(COPY + DetailPart.ResponseBody.name).performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { clipboard.copied != null }
                val copied = clipboard.copied!!.text
                assertTrue(copied.length > 20 && document.startsWith(copied), copied)

                onNodeWithTag(DETAIL_SEARCH_OPEN).performClick()
                onNodeWithTag(DETAIL_SEARCH).performTextInput("item-1\"")
                awaitText("1 of 1")
            }
        }

    @Test
    fun `a JSON body opens as JSON whatever its type, wraps, and cuts long values in the view only`() =
        withStorage { storage ->
            val token = "x".repeat(4_990) + "needle" + "y".repeat(4)
            val note = List(25) { "word$it" }.joinToString(" ")
            val sent = """{"token":"$token","note":"$note"}"""
            val live = LiveTraffic()
            live.record(
                envelope(
                    RequestStarted(
                        request_id = "r1",
                        method = "POST",
                        url = URL,
                        headers = listOf(Header("Content-Type", "text/plain")),
                        body = sent.encodeUtf8(),
                    ),
                    1_000,
                ),
                envelope(
                    ResponseReceived(request_id = "r1", status_code = 200, body = """{"ok":true}""".encodeUtf8()),
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

                awaitText(URL)
                onNodeWithText(URL).performClick()
                showTab("Body")
                awaitText("\"token\": \"${"x".repeat(200)}… (4800 more)\"", substring = true)
                awaitText("\"ok\": true", substring = true)
                val root = onRoot().fetchSemanticsNode().boundsInRoot
                for (part in listOf("… (4800 more)", "word24")) {
                    val bounds = onNodeWithText(part, substring = true).fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.right <= root.right, "$part runs past the window: $bounds")
                }
                screenshot("detail-body.png")

                onNodeWithTag(COPY + DetailPart.RequestBody.name).performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { clipboard.copied != null }
                assertEquals(sent, clipboard.copied!!.text)

                onNodeWithTag(DETAIL_SEARCH_OPEN).performClick()
                onNodeWithTag(DETAIL_SEARCH).performTextInput("needle")
                awaitText("No match · 1 in a cut value")
                onNodeWithTag(DETAIL_SEARCH).performTextReplacement("word2")
                awaitText("1 of 6")
            }
        }

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
                assertTabOpen("Body")
                showTab("Summary")
                awaitText("Content-Type")
                onNodeWithTag(DETAIL_META).assertTextContains("120 ms · 8 B ↓ · ", substring = true)
                awaitText("page=2")
                awaitText("application/json")
                assertEquals(URL, copy(DetailPart.Url))
                onNodeWithTag(COPY_NOTE).assertExists()
                screenshot("detail-summary.png")

                showTab("Headers")
                awaitText("X-Request-Id")
                onNodeWithText("42").assertExists()
                onNodeWithText("RESPONSE · 2").assertExists()
                assertEquals("Content-Type: application/json", copy(DetailPart.RequestHeaders))
                assertEquals("Content-Type: application/json\nX-Request-Id: 42", copy(DetailPart.ResponseHeaders))
                screenshot("detail-headers.png")

                showTab("Body")
                awaitText("{\n    \"id\": 7\n}")
                assertEquals("{\"name\":\"a\"}", copy(DetailPart.RequestBody))
                assertEquals("{\"id\":7}", copy(DetailPart.ResponseBody))

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
                showTab("Summary")
                clipboard.copied = null
                onNodeWithTag(MORE_ACTIONS).performClick()
                onNodeWithTag(COPY_CURL_ACTION).performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { clipboard.copied != null }
                assertEquals(command, clipboard.copied!!.text, "the menu copies the cURL tab's command")
                showTab("cURL")

                onNodeWithTag(DETAIL_SEARCH_OPEN).performClick()
                onNodeWithTag(DETAIL_SEARCH).performTextInput("application")
                awaitText("3 matches")
                showTab("Summary · 1")
                showTab("Headers · 2")
                screenshot("detail-search.png")
                onNodeWithTag(DETAIL_SEARCH).performTextReplacement("")
                onNodeWithTag(DETAIL_MATCHES).assertDoesNotExist()
                showTab("Headers")

                showTab("Body")
                onNodeWithTag(DETAIL_SEARCH).performTextInput("\"")
                awaitText("1 of 6")
                onNodeWithTag(NEXT_MATCH).performClick()
                awaitText("2 of 6")
                onNodeWithTag(PREVIOUS_MATCH).performClick()
                onNodeWithTag(PREVIOUS_MATCH).performClick()
                awaitText("6 of 6")
                screenshot("detail-jump.png")
                onNodeWithTag(SEARCH_CLEAR).performClick()
                onNodeWithTag(DETAIL_MATCHES).assertDoesNotExist()
            }
        }

    private fun gzip(text: String): ByteString {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray().toByteString()
    }
}
