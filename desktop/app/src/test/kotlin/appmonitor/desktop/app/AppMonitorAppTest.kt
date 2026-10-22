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
import appmonitor.desktop.app.store.Device
import appmonitor.desktop.app.store.HttpExchange
import appmonitor.desktop.app.store.TrafficState
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.ServerConfig
import appmonitor.tools.fakeclient.Target
import appmonitor.tools.fakeclient.replay
import appmonitor.v1.Header
import appmonitor.v1.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
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
    fun `two exchanges with the same body open in their own default format`() {
        val body = "{\"a\":1}".encodeUtf8()
        val device = Device("s1", "Pixel", Platform.PLATFORM_ANDROID, "Android 16", "app", "1.0")
        val state =
            TrafficState(
                devices = listOf(device),
                exchanges =
                    listOf(
                        HttpExchange(
                            "s1",
                            "r1",
                            "POST",
                            "https://a.example/json",
                            listOf(Header("Content-Type", "application/json")),
                            body,
                        ),
                        HttpExchange("s1", "r2", "POST", "https://a.example/text", listOf(Header("Content-Type", "text/plain")), body),
                    ),
            )

        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { AppMonitorApp(state, "Listening") }

            onNodeWithText("https://a.example/json").performClick()
            onNodeWithText("{\n    \"a\": 1\n}").assertExists()

            onNodeWithText("https://a.example/text").performClick()
            onNodeWithText("{\"a\":1}").assertExists()
            onNodeWithText("{\n    \"a\": 1\n}").assertDoesNotExist()
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
    fun `reports an Error thrown by the server start`() {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val statuses = mutableListOf<String>()
        var stopped = false
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                runServer(
                    server,
                    TrafficStore(),
                    start = { throw NoClassDefFoundError("javax/jmdns/JmDNS") },
                    stop = { stopped = true },
                ) { statuses += it }
            }
        }

        assertEquals(listOf("Server failed: javax/jmdns/JmDNS"), statuses)
        assertTrue(stopped)
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

    @Test
    fun `exit waits for the server stop before returning`() {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val listening = CompletableDeferred<Int>()
        var stopped = false
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                runUntilExit(
                    serve = {
                        runServer(
                            server,
                            TrafficStore(),
                            start = { server.start().also { listening.complete(it) } },
                            stop = {
                                delay(200)
                                server.stop()
                                stopped = true
                            },
                        ) {}
                    },
                ) { listening.await() }
            }
        }

        assertTrue(stopped)
        ServerSocket(listening.getCompleted(), 0, InetAddress.getByName("127.0.0.1")).close()
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
