package appmonitor.desktop.app

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.desktop.storage.MockSource
import appmonitor.desktop.storage.MockWrite
import appmonitor.desktop.storage.Storage
import appmonitor.protocol.Protocol
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.Hello
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import appmonitor.v1.Platform
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

// The README's animated captures from invented traffic (AGENTS.md, "Nothing private"). Run only by `demoGifs`.
@OptIn(ExperimentalTestApi::class)
class DemoCaptures {
    private val out = File(System.getProperty("demoGifDir") ?: "build/demo-gifs")

    private val hello =
        Hello(
            protocol_version = Protocol.VERSION,
            platform = Platform.PLATFORM_ANDROID,
            device_id = "demo-pixel",
            device_name = "Pixel 9",
            os_version = "Android 16",
            app_id = "example.shop",
            app_name = "Shop",
            app_version = "2.4.0",
            channels = listOf(Protocol.CHANNEL_NETWORK),
        )

    private class Call(
        val id: String,
        val method: String,
        val url: String,
        val status: Int?,
        val ms: Long,
        val requestBody: String? = null,
        val responseBody: String? = null,
        val error: String? = null,
        val mockRule: Long? = null,
    )

    private val json = listOf(Header("Content-Type", "application/json; charset=utf-8"))

    private val catalog =
        """{"page":1,"items":[{"id":"sku-1042","name":"Trail running shoes","price":{"amount":89.9,"currency":"EUR"},""" +
            """"stock":12},{"id":"sku-2210","name":"Rain jacket","price":{"amount":129.0,"currency":"EUR"},"stock":3}],""" +
            """"next":"/v1/catalog?page=2"}"""

    private val calls =
        listOf(
            Call("c1", "GET", "https://api.shop.example/v1/session", 200, 84, responseBody = """{"user":"guest-7f3a","cart":2}"""),
            Call("c2", "GET", "https://api.shop.example/v1/catalog?page=1", 200, 212, responseBody = catalog),
            Call("c3", "GET", "https://cdn.shop.example/img/banner-winter.webp", 200, 47),
            Call(
                "c4",
                "POST",
                "https://api.shop.example/v1/cart/items",
                201,
                156,
                requestBody = """{"sku":"sku-1042","size":"42","quantity":1}""",
                responseBody = """{"cart":{"id":"cart-91c2","items":3,"total":{"amount":238.8,"currency":"EUR"}}}""",
            ),
            Call(
                "c5",
                "GET",
                "https://api.shop.example/v1/recommendations?for=sku-1042",
                500,
                1_034,
                responseBody = """{"error":"upstream timeout"}""",
            ),
            Call(
                "c6",
                "GET",
                "https://api.shop.example/v1/catalog?page=2",
                200,
                188,
                responseBody = """{"page":2,"items":[],"next":null}""",
            ),
            Call(
                "c7",
                "POST",
                "https://events.shop.example/collect",
                204,
                31,
                requestBody = """{"event":"add_to_cart","sku":"sku-1042"}""",
            ),
            Call("c8", "PUT", "https://api.shop.example/v1/profile/address", null, 10_000, error = "The request timed out."),
        )

    private fun Call.envelopes(start: Long): List<Envelope> {
        val started =
            envelope(
                RequestStarted(
                    request_id = id,
                    method = method,
                    url = url,
                    headers = if (requestBody != null) json else emptyList(),
                    body = requestBody?.encodeUtf8() ?: okio.ByteString.EMPTY,
                    capture_layer = CaptureLayer.CAPTURE_LAYER_PLATFORM,
                    mock_rule_id = mockRule,
                ),
                start,
            )
        val ended =
            if (error != null) {
                envelope(RequestFailed(request_id = id, error = error), start + ms)
            } else {
                envelope(
                    ResponseReceived(
                        request_id = id,
                        status_code = status!!,
                        headers = if (responseBody != null) json else emptyList(),
                        body = responseBody?.encodeUtf8() ?: okio.ByteString.EMPTY,
                    ),
                    start + ms,
                )
            }
        return listOf(started, ended)
    }

    /** A connected session that already sent [calls], left open so the device shows as connected. */
    private fun LiveTraffic.open(calls: List<Call>) =
        runBlocking {
            connected(SESSION, hello, "192.168.1.23")
            calls.forEachIndexed { i, call -> call.envelopes(T0 + i * 700L).forEach { received(SESSION, it) } }
        }

    private fun ComposeUiTest.frame(
        gif: GifWriter,
        delayMs: Int,
    ) {
        waitForIdle()
        gif.add(onRoot().captureToImage().toAwtImage(), delayMs)
    }

    private fun scene(
        name: String,
        storage: Storage,
        live: LiveTraffic,
        script: ComposeUiTest.(GifWriter) -> Unit,
    ) {
        val file = File(out, name)
        val gif = GifWriter(file)
        runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
            setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }
            awaitText("Listening", substring = true)
            script(gif)
        }
        gif.write()
        println("demoGifs: wrote $file, ${file.length() / 1024} KiB")
        assertTrue(file.length() <= BUDGET_BYTES, "$name is ${file.length()} bytes, over its $BUDGET_BYTES budget")
    }

    @Test
    fun traffic() =
        withStorage { storage ->
            val live = LiveTraffic()
            scene("demo-traffic.gif", storage, live) { gif ->
                frame(gif, 1_200)
                runBlocking { live.connected(SESSION, hello, "192.168.1.23") }
                frame(gif, 600)
                calls.forEachIndexed { i, call ->
                    val (started, ended) = call.envelopes(T0 + i * 700L)
                    runBlocking { live.received(SESSION, started) }
                    frame(gif, 250)
                    runBlocking { live.received(SESSION, ended) }
                    frame(gif, 450)
                }
                onNodeWithText("https://api.shop.example/v1/catalog?page=1").performClick()
                showTab("Body")
                awaitText("Trail running shoes", substring = true)
                frame(gif, 3_000)
            }
        }

    @Test
    fun detail() =
        withStorage { storage ->
            val live = LiveTraffic()
            live.open(calls)
            scene("demo-detail.gif", storage, live) { gif ->
                awaitText("https://api.shop.example/v1/cart/items")
                frame(gif, 900)
                onNodeWithText("https://api.shop.example/v1/cart/items").performClick()
                for ((tab, ms) in listOf("Summary" to 1_600, "Headers" to 1_600, "Body" to 2_000, "Timing" to 1_600, "cURL" to 2_000)) {
                    showTab(tab)
                    assertTabOpen(tab)
                    frame(gif, ms)
                }
            }
        }

    @Test
    fun mock() =
        withStorage { storage ->
            val live = LiveTraffic()
            val rule =
                MockRule(
                    host = "api.shop.example",
                    path = "/v1/checkout",
                    response = MockResponse(status_code = 503),
                )
            val id =
                assertIs<MockWrite.Saved>(
                    runBlocking { storage.createMock(hello.app_id, "checkout down", rule, MockSource.WINDOW) },
                ).id
            val before = calls.take(4)
            val checkout =
                Call(
                    "m1",
                    "POST",
                    "https://api.shop.example/v1/checkout",
                    503,
                    3,
                    requestBody = """{"cart":"cart-91c2","payment":"card"}""",
                    responseBody = """{"error":"maintenance","retryAfter":120}""",
                    mockRule = id,
                )
            live.open(before)
            scene("demo-mock.gif", storage, live) { gif ->
                awaitText("https://api.shop.example/v1/cart/items")
                frame(gif, 1_200)
                val (started, ended) = checkout.envelopes(T0 + 10_000L)
                runBlocking { live.received(SESSION, started) }
                frame(gif, 300)
                runBlocking { live.received(SESSION, ended) }
                awaitText("https://api.shop.example/v1/checkout")
                frame(gif, 1_400)
                onNodeWithText("https://api.shop.example/v1/checkout").performClick()
                showTab("Summary")
                awaitText("Mocked by rule \"checkout down\"")
                frame(gif, 2_200)
                showTab("Body")
                awaitText("maintenance", substring = true)
                frame(gif, 2_400)
            }
        }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
        const val SESSION = "demo"
        const val BUDGET_BYTES = 1_500_000L

        // 2026-11-27 13:42:07 UTC: a fixed time, so a rerun changes nothing but the local clock's offset.
        const val T0 = 1_795_786_927_000L
    }
}
