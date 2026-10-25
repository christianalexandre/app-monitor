package appmonitor.desktop.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.store.ApprovalStore
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.traffic.RUNS
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.ServerConfig
import appmonitor.desktop.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Not a unit test: run by `liveScreenshots` only, against a real device running the sample (ADR-0031).
 * Real server on the default port with mDNS, the window's composable rendered offscreen, then the store reopened.
 */
@OptIn(ExperimentalTestApi::class)
class LiveDeviceScreenshots {
    private val name = System.getProperty("liveName", "device")
    private val exchanges = System.getProperty("liveExchanges", "5").toInt()
    private val timeoutMs = System.getProperty("liveTimeoutMs", "300000").toLong()
    private val advertise = System.getProperty("liveAdvertise", "true").toBoolean()
    private val dir: Path = System.getProperty("liveDataDir")?.let { Path.of(it) } ?: createTempDirectory("live-screenshots")

    @Test
    fun `approve a real device, show its traffic, and again after a reopen`() {
        capture()
        reopen()
    }

    private fun capture() {
        lateinit var server: AppMonitorServer
        val storage = Storage.open(dir)
        val approvals = runBlocking { ApprovalStore.open(storage) { server.disconnect(it) } }
        server = AppMonitorServer(ServerConfig(advertise = advertise), approval = approvals.approvals)
        val store = TrafficStore(storage)
        val scope = CoroutineScope(Dispatchers.Default)
        val port = runBlocking { server.start() }
        scope.launch { store.collect(server.sessions) }
        try {
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent {
                    val pending by approvals.pending.collectAsState()
                    val answered by remember { approvals.stored }.collectAsState(emptyList())
                    AppMonitorApp(
                        storage,
                        "Listening on port $port",
                        pending,
                        { device, approved -> scope.launch { approvals.decide(device, approved) } },
                        answered,
                    )
                }
                println("liveScreenshots: data in $dir, listening on $port, mDNS ${if (advertise) "on" else "off"}, waiting for a device")
                waitUntil(timeoutMillis = timeoutMs) { onAllNodesWithText("Approve").fetchSemanticsNodes().isNotEmpty() }
                screenshot("$name-approval.png")
                onNodeWithText("Approve").performClick()

                println("liveScreenshots: approved, waiting for $exchanges finished exchanges")
                waitUntil(timeoutMillis = timeoutMs) {
                    runBlocking { storage.allExchanges() }.count { it.statusCode != null || it.failed } >= exchanges
                }
                showJsonAndFrames()
            }
        } finally {
            scope.cancel()
            runBlocking {
                server.stop()
                storage.close(5.seconds)
            }
        }
    }

    private fun reopen() {
        val storage = Storage.open(dir)
        try {
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Reopened") }
                println("liveScreenshots: store reopened, opening the earlier run")
                onNodeWithTag(RUNS).performClick()
                onAllNodesWithText(" – ", substring = true).onFirst().performClick()
                waitUntil(timeoutMillis = timeoutMs) { onAllNodesWithText("/json", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("/json", substring = true).onFirst().performClick()
                waitFor("\"slideshow\"")
                screenshot("$name-reopen.png")
            }
        } finally {
            runBlocking { storage.close(5.seconds) }
        }
    }

    private fun ComposeUiTest.showJsonAndFrames() {
        onAllNodesWithText("/json", substring = true).onFirst().performClick()
        waitFor("\"slideshow\"")
        screenshot("$name-traffic.png")
        onAllNodesWithText("echo.websocket.org", substring = true).onFirst().performClick()
        waitFor("WebSocket frames")
        screenshot("$name-frames.png")
    }

    private fun ComposeUiTest.waitFor(text: String) =
        waitUntil(timeoutMillis = timeoutMs) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.screenshot(file: String) {
        val out = File(System.getProperty("liveScreenshotDir"), file)
        out.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
        println("liveScreenshots: wrote $out")
    }
}
