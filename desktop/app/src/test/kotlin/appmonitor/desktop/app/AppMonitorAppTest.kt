package appmonitor.desktop.app

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.store.TrafficState
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.ServerConfig
import appmonitor.tools.fakeclient.Target
import appmonitor.tools.fakeclient.replay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppMonitorAppTest {
    @Test
    fun `shows fake client traffic received by the server`() {
        val state = receiveFixtureTraffic()

        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { AppMonitorApp(state, "Listening on port 47474") }

            onNodeWithText("Pixel 9 (emulator)").assertExists()
            onNodeWithText("Android 16 · disconnected").assertExists()
            onNodeWithText("POST").assertExists()
            onNodeWithText("201").assertExists()
            onNodeWithText("failed").assertExists()

            onNodeWithText(URL).performClick()
            onAllNodesWithText(URL, substring = true).assertCountEquals(2)
            onNodeWithText("Content-Type: application/json").assertExists()
            onNodeWithText("{\n    \"name\": \"café\"\n}").assertExists()
            onNodeWithText("00000000  00 7f 80 ff", substring = true).assertExists()

            val screenshot = File(System.getProperty("screenshotDir"), "app.png")
            screenshot.parentFile.mkdirs()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", screenshot)
        }
    }

    @Test
    fun `shows the failed status when the server start throws a non-IO exception`() {
        // A stopped server is single use: start throws IllegalStateException.
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val statuses = mutableListOf<String>()
        runBlocking {
            server.stop()
            withTimeout(TIMEOUT_MS) { runServer(server, TrafficStore()) { statuses += it } }
        }

        assertEquals(listOf("Server failed: Server is single use: create a new instance"), statuses)
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { AppMonitorApp(TrafficState(), statuses.last()) }

            onNodeWithText(statuses.last()).assertExists()
        }
    }

    @Test
    fun `reports a stop failure without throwing or hiding the start failure`() {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val statuses = mutableListOf<String>()
        runBlocking {
            server.stop()
            withTimeout(TIMEOUT_MS) {
                runServer(server, TrafficStore(), stop = { throw IOException("stop failed") }) { statuses += it }
            }
        }

        assertEquals(
            listOf("Server failed: Server is single use: create a new instance", "Server stop failed: stop failed"),
            statuses,
        )
    }

    // Real server and fake client: the UI renders what actually crossed the wire.
    private fun receiveFixtureTraffic(): TrafficState =
        runBlocking {
            val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
            val store = TrafficStore()
            try {
                val port = server.start()
                val collecting = launch(Dispatchers.Default) { store.collect(server.sessions) }
                withTimeout(TIMEOUT_MS) {
                    replay(Target("127.0.0.1", port))
                    store.state.first { state -> state.devices.isNotEmpty() && state.devices.none { it.connected } }
                }
                collecting.cancel()
                store.state.value
            } finally {
                server.stop()
            }
        }

    private companion object {
        const val URL = "https://api.example.com/v1/items?page=2"
        const val TIMEOUT_MS = 10_000L
    }
}
