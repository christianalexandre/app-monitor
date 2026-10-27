package appmonitor.desktop.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.traffic.COPY
import appmonitor.desktop.feature.traffic.CURL
import appmonitor.desktop.feature.traffic.DetailPart
import appmonitor.desktop.feature.traffic.EXPORT_HAR
import appmonitor.desktop.mcp.assertHar12
import appmonitor.desktop.mcp.runCurl
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.v1.Header
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ExportUiTest {
    private val body = "name=it's \"quoted\" \$HOME " + "x".repeat(20_000)

    @Test
    fun `the HAR file passes the HAR 1_2 check and the copied cURL sends the stored request`() =
        withStorage { storage ->
            val live = LiveTraffic()
            storage.record(envelope(RequestStarted(request_id = "old", method = "GET", url = "https://api.example.com/old")))
            live.record(
                envelope(
                    RequestStarted(
                        request_id = "r1",
                        method = "POST",
                        url = "http://127.0.0.1:1/items?q=a%20b",
                        headers = listOf(Header("Content-Type", "text/plain"), Header("X-Dup", "1"), Header("X-Dup", "2")),
                        body = body.encodeUtf8(),
                    ),
                    1_000,
                ),
                envelope(ResponseReceived(request_id = "r1", status_code = 201, body = "{}".encodeUtf8()), 1_250),
                envelope(RequestStarted(request_id = "r2", method = "GET", url = "https://api.example.com/feed"), 2_000),
                sessionId = "s2",
            )
            val file = File(createTempDirectory("har").toFile(), "run.har")
            val clipboard = FakeClipboard()
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent {
                    CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                        AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live, pickHarFile = { file })
                    }
                }

                onNodeWithTag(EXPORT_HAR).performClick()
                awaitText("Exported 2 exchanges to run.har")
                val har = Json.parseToJsonElement(file.readText()).jsonObject
                assertHar12(har)
                val entries = har["log"]!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject }
                assertEquals(
                    listOf("http://127.0.0.1:1/items?q=a%20b", "https://api.example.com/feed"),
                    entries.map { it["request"]!!.jsonObject["url"]!!.jsonPrimitive.content },
                    "the live list only, oldest first",
                )
                val postData = entries[0]["request"]!!.jsonObject["postData"]!!.jsonObject
                assertEquals(body, postData["text"]!!.jsonPrimitive.content, "bodies whole, past export_har's 16 KiB cap")

                onNodeWithText("http://127.0.0.1:1/items?q=a%20b").performClick()
                // The detail pane reads the exchange off the UI thread.
                awaitText("cURL")
                onNodeWithText("cURL").performClick()
                awaitTag(CURL)
                onNodeWithTag(COPY + DetailPart.Curl.name).performClick()
                awaitText("Copied")
                screenshot("export.png")
            }
            val command = clipboard.copied!!.text
            assertTrue(command.startsWith("curl --globoff -X 'POST' 'http://127.0.0.1:1/items?q=a%20b'"), command)
            val received = runCurl(command)
            assertEquals("POST", received.method)
            assertEquals("/items?q=a%20b", received.uri)
            assertEquals(body.encodeUtf8(), received.body)
            assertEquals(listOf("1", "2"), received.headers.filter { it.first == "x-dup" }.map { it.second })
            assertTrue("content-type" to "text/plain" in received.headers, "${received.headers}")
            check(file.parentFile.deleteRecursively())
        }
}
