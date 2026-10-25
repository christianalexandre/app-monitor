package appmonitor.desktop.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.traffic.BENCHMARKS
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Header
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import kotlin.test.Test
import kotlin.test.assertEquals

// M8.7: a gRPC call ended with a status other than OK, from the adapter and from the proxy, as an error.
@OptIn(ExperimentalTestApi::class)
class GrpcStatusUiTest {
    @Test
    fun `a failed grpc call from each writer shows its grpc status and counts as an error`() =
        withStorage { storage ->
            val live = LiveTraffic()
            live.record(
                envelope(call("a", "https://api.example.com/test.Echo/Unary", CaptureLayer.CAPTURE_LAYER_ADAPTER), 1_000),
                envelope(ended("a", 13, "broke"), 1_010),
                envelope(call("p", "http://127.0.0.1:50051/test.Echo/Missing", CaptureLayer.CAPTURE_LAYER_PROXY), 2_000),
                envelope(ended("p", 5, "no such thing"), 2_010),
            )
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }

                awaitText("https://api.example.com/test.Echo/Unary")
                onNodeWithText("grpc 13").assertExists()
                onNodeWithText("grpc 5").performClick()
                awaitText("Status grpc 5 · Duration 10 ms · Proxy layer")

                onNodeWithTag(BENCHMARKS).performClick()
                awaitText("POST api.example.com/test.Echo/Unary")
                onNodeWithText("POST 127.0.0.1/test.Echo/Missing").assertExists()
                val texts =
                    onNodeWithText("POST 127.0.0.1/test.Echo/Missing", useUnmergedTree = false)
                        .fetchSemanticsNode()
                        .config[SemanticsProperties.Text]
                        .map { it.text }
                assertEquals(2, texts.count { it == "100.0 %" })
            }
        }

    private fun call(
        requestId: String,
        url: String,
        layer: CaptureLayer,
    ) = RequestStarted(
        request_id = requestId,
        method = "POST",
        url = url,
        headers = listOf(Header("content-type", "application/grpc")),
        capture_layer = layer,
    )

    private fun ended(
        requestId: String,
        status: Int,
        message: String,
    ) = ResponseReceived(
        request_id = requestId,
        status_code = 200,
        headers =
            listOf(
                Header("content-type", "application/grpc"),
                Header("grpc-status", "$status"),
                Header("grpc-message", message),
            ),
    )
}
