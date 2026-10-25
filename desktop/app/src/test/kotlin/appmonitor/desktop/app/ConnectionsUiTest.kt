package appmonitor.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.traffic.BENCHMARKS
import appmonitor.desktop.feature.traffic.CONNECTION_BYTES
import appmonitor.desktop.feature.traffic.EXPORT_HAR
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.tools.fakeclient.Fixtures
import appmonitor.v1.CaptureLayer
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

// M8.4: the `CONNECTION` fixtures, stamped as sent, in the list, detail, benchmarks and HAR.
@OptIn(ExperimentalTestApi::class)
class ConnectionsUiTest {
    @Test
    fun `a connection shows its bytes and no status, headers, body or cURL`() =
        withStorage { storage ->
            val live = LiveTraffic()
            val refused =
                RequestStarted(
                    request_id = "conn-0002",
                    method = "CONNECT",
                    url = "tcp://10.0.0.7:8443",
                    capture_layer = CaptureLayer.CAPTURE_LAYER_CONNECTION,
                )
            val failed = RequestFailed(request_id = "conn-0002", error = "NSPOSIXErrorDomain 61: Connection refused")
            val at = Fixtures.connection.first().timestamp_ms
            live.record(
                *(Fixtures.traffic.take(2) + Fixtures.connection + envelope(refused, at) + envelope(failed, at + 20)).toTypedArray(),
            )
            val file = File(createTempDirectory("har").toFile(), "run.har")
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live, pickHarFile = { file }) }

                awaitText("tcp://push.example.com:5223")
                onNodeWithText("closed").assertExists()
                onNodeWithText("failed").assertExists()
                onNodeWithTag(EXPORT_HAR).performClick()
                awaitText("Exported 1 exchanges to run.har; 2 connections left out")

                onNodeWithText("tcp://10.0.0.7:8443").performClick()
                awaitText("NSPOSIXErrorDomain 61: Connection refused")
                onNodeWithText("tcp://push.example.com:5223").performClick()
                awaitText("Sent 512 B · Received 2048 B")
                onNodeWithText("Status closed · Duration 1.50 s · Connection layer").assertExists()
                onNodeWithTag(CONNECTION_BYTES).assertExists()
                onNodeWithText("cURL").assertDoesNotExist()
                onNodeWithText("Headers").assertDoesNotExist()
                screenshot("connections.png")

                onNodeWithTag(BENCHMARKS).performClick()
                awaitText("CONNECT push.example.com:5223")
                onNodeWithText("CONNECT 10.0.0.7:8443").assertExists()
            }
            check(file.parentFile.deleteRecursively())
        }
}
