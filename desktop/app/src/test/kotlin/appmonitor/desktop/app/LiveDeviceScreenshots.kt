package appmonitor.desktop.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.DeviceApprovals
import appmonitor.desktop.server.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Not a unit test: run by `liveScreenshots` only, against a real device running the sample (ADR-0031).
 * Real server on the default port with mDNS, the window's composable rendered offscreen.
 */
@OptIn(ExperimentalTestApi::class)
class LiveDeviceScreenshots {
    private val name = System.getProperty("liveName", "device")
    private val exchanges = System.getProperty("liveExchanges", "5").toInt()
    private val timeoutMs = System.getProperty("liveTimeoutMs", "300000").toLong()
    private val advertise = System.getProperty("liveAdvertise", "true").toBoolean()

    @Test
    fun `approve a real device and show its traffic`() {
        val approvals = DeviceApprovals()
        val server = AppMonitorServer(ServerConfig(advertise = advertise), approval = approvals)
        val store = TrafficStore()
        val scope = CoroutineScope(Dispatchers.Default)
        val port = runBlocking { server.start() }
        scope.launch { store.collect(server.sessions) }
        try {
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent {
                    val state by store.state.collectAsState()
                    val pending by approvals.pending.collectAsState()
                    AppMonitorApp(state, "Listening on port $port", pending, approvals::decide)
                }
                println("liveScreenshots: listening on $port, mDNS ${if (advertise) "on" else "off"}, waiting for a device")
                waitUntil(timeoutMillis = timeoutMs) { onAllNodesWithText("Approve").fetchSemanticsNodes().isNotEmpty() }
                screenshot("$name-approval.png")
                onNodeWithText("Approve").performClick()

                println("liveScreenshots: approved, waiting for $exchanges finished exchanges")
                waitUntil(timeoutMillis = timeoutMs) {
                    store.state.value.exchanges
                        .count { it.statusCode != null || it.error != null } >= exchanges
                }
                onAllNodesWithText("/json", substring = true).onFirst().performClick()
                waitUntil(
                    timeoutMillis = timeoutMs,
                ) { onAllNodesWithText("\"slideshow\"", substring = true).fetchSemanticsNodes().isNotEmpty() }
                screenshot("$name-traffic.png")
                onAllNodesWithText("echo.websocket.org", substring = true).onFirst().performClick()
                waitUntil(
                    timeoutMillis = timeoutMs,
                ) { onAllNodesWithText("WebSocket frames", substring = true).fetchSemanticsNodes().isNotEmpty() }
                screenshot("$name-frames.png")
            }
        } finally {
            scope.cancel()
            runBlocking { server.stop() }
        }
    }

    private fun ComposeUiTest.screenshot(file: String) {
        val out = File(System.getProperty("liveScreenshotDir"), file)
        out.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
        println("liveScreenshots: wrote $out")
    }
}
