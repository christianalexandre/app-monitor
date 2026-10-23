package appmonitor.desktop.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.store.CapturedBody
import appmonitor.desktop.app.store.CapturedFrame
import appmonitor.desktop.app.store.Device
import appmonitor.desktop.app.store.HttpExchange
import appmonitor.desktop.app.store.TrafficState
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.DeviceApprovals
import appmonitor.desktop.server.DeviceKey
import appmonitor.desktop.server.PendingDevice
import appmonitor.desktop.server.ServerConfig
import appmonitor.protocol.Protocol
import appmonitor.tools.fakeclient.Target
import appmonitor.tools.fakeclient.replay
import appmonitor.v1.Header
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import appmonitor.v1.WebSocketFrame.Direction
import appmonitor.v1.WebSocketFrame.Opcode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

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
            awaitText("{\n    \"name\": \"café\"\n}")
            awaitText("00000000  00 7f 80 ff", substring = true)
            onNodeWithText("Truncated: 4 of 2097152 bytes shown").assertExists()
            screenshot("app.png")

            onNodeWithText("req-0003").performClick()
            onNodeWithText("WebSocket frames (1)").assertExists()
            onNodeWithText("↓ received · TEXT · 4 B").assertExists()
            onNodeWithText("ping").performClick()
            waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText("ping").fetchSemanticsNodes().size == 2 }
            screenshot("frames.png")
        }
    }

    @Test
    fun `frames of a websocket exchange show in order with their truncation`() {
        val device = Device("s1", "Pixel", Platform.PLATFORM_ANDROID, "Android 16", "app", "1.0")
        val frames =
            listOf(
                CapturedFrame(Direction.DIRECTION_SENT, Opcode.OPCODE_TEXT, CapturedBody("hello".encodeUtf8(), originalSize = 5), 1_010),
                CapturedFrame(Direction.DIRECTION_RECEIVED, Opcode.OPCODE_TEXT, CapturedBody("hell".encodeUtf8(), true, 11), 1_250),
                CapturedFrame(
                    Direction.DIRECTION_SENT,
                    Opcode.OPCODE_CLOSE,
                    CapturedBody(Buffer().writeShort(1000).writeUtf8("bye").readByteString()),
                    1_500,
                ),
            )
        val exchange = HttpExchange("s1", "r1", "GET", "wss://a.example/ws", startedAtMs = 1_000, statusCode = 101, frames = frames)

        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { AppMonitorApp(TrafficState(listOf(device), listOf(exchange)), "Listening") }

            onNodeWithText("wss://a.example/ws").performClick()
            onNodeWithText("WebSocket frames (3)").assertExists()
            onNodeWithText("↑ sent · TEXT · 5 B · +10 ms").assertExists()
            onNodeWithText("↓ received · TEXT · 4 of 11 B · +250 ms").assertExists()
            onNodeWithText("↑ sent · CLOSE · 5 B · +500 ms").assertExists()
            onNodeWithText("1000 bye").assertExists()

            onNodeWithText("hell").performClick()
            onNodeWithText("Truncated: 4 of 11 bytes shown").assertExists()
            waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText("hell").fetchSemanticsNodes().size == 2 }
        }
    }

    @Test
    fun `a body at the protocol limit renders off the UI thread`() {
        val element = """{"id":12345,"name":"item","tags":["a","b"]}"""
        val json = List(Protocol.MAX_BODY_BYTES / (element.length + 1)) { element }.joinToString(",", "[", "]").encodeUtf8()
        val device = Device("s1", "Pixel", Platform.PLATFORM_ANDROID, "Android 16", "app", "1.0")
        val exchange =
            HttpExchange(
                "s1",
                "r1",
                "GET",
                "https://a.example/big",
                statusCode = 200,
                responseHeaders = listOf(Header("Content-Type", "application/json")),
                responseBody = CapturedBody(json, originalSize = json.size.toLong()),
            )

        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { AppMonitorApp(TrafficState(listOf(device), listOf(exchange)), "Listening") }

            val opened =
                measureTime {
                    onNodeWithText("https://a.example/big").performClick()
                    waitForIdle()
                }
            assertTrue(json.size in Protocol.MAX_BODY_BYTES - element.length..Protocol.MAX_BODY_BYTES, "body is ${json.size} bytes")
            assertTrue(opened < 2.seconds, "opening the exchange blocked the UI thread for $opened")
            awaitText("[\n    {\n        \"id\": 12345,", substring = true)
        }
    }

    @Test
    fun `a pending device is admitted by clicking Approve in the dialog`() {
        val approvals = DeviceApprovals()
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false), approvals)
        val store = TrafficStore()
        runBlocking {
            val port = server.start()
            val collecting = launch(Dispatchers.Default) { store.collect(server.sessions) }
            val replaying = async(Dispatchers.IO) { runCatching { replay(Target("127.0.0.1", port)) } }
            try {
                runDesktopComposeUiTest(width = 1280, height = 800) {
                    setContent {
                        val state by store.state.collectAsState()
                        val pending by approvals.pending.collectAsState()
                        AppMonitorApp(state, "Listening", pending, approvals::decide)
                    }

                    waitUntil(timeoutMillis = TIMEOUT_MS) { approvals.pending.value.isNotEmpty() }
                    onNodeWithText("Pixel 9 (emulator) — dev.example.sample wants to connect").assertExists()
                    onNodeWithText("Pixel 9 (emulator)").assertDoesNotExist()

                    onNodeWithText("Approve").performClick()
                    waitUntil(timeoutMillis = TIMEOUT_MS) {
                        store.state.value.devices
                            .run { isNotEmpty() && none { it.connected } }
                    }
                    onNodeWithText("Approve").assertDoesNotExist()
                    onNodeWithText("Pixel 9 (emulator)").assertExists()
                    onNodeWithText("201").assertExists()
                }
                assertTrue(withTimeout(TIMEOUT_MS) { replaying.await() }.isSuccess)
            } finally {
                collecting.cancel()
                server.stop()
            }
        }
    }

    @Test
    fun `Reject in the dialog decides the first pending device and shows the rest waiting`() {
        val pending =
            listOf("app.a", "app.b").map {
                PendingDevice(DeviceKey("d1", it), Hello(device_name = "Pixel", device_id = "d1", app_id = it, os_version = "Android 16"))
            }
        val decisions = mutableListOf<Pair<DeviceKey, Boolean>>()

        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { AppMonitorApp(TrafficState(), "Listening", pending) { key, approved -> decisions += key to approved } }

            onNodeWithText("Pixel — app.a wants to connect").assertExists()
            onNodeWithText("1 more waiting").assertExists()
            screenshot("approval.png")
            onNodeWithText("Reject").performClick()
        }

        assertEquals(listOf(DeviceKey("d1", "app.a") to false), decisions)
    }

    @Test
    fun `two exchanges with the same body open in their own default format`() {
        val body = CapturedBody("{\"a\":1}".encodeUtf8())
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
            awaitText("{\n    \"a\": 1\n}")

            onNodeWithText("https://a.example/text").performClick()
            awaitText("{\"a\":1}")
            onNodeWithText("{\n    \"a\": 1\n}").assertDoesNotExist()
        }
    }

    @Test
    fun `an unread body shows its truncation note and a whole one none`() {
        val device = Device("s1", "Pixel", Platform.PLATFORM_ANDROID, "Android 16", "app", "1.0")
        val exchange =
            HttpExchange(
                "s1",
                "r1",
                "POST",
                "https://a.example/upload",
                requestBody = CapturedBody(truncated = true),
                statusCode = 204,
            )

        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { AppMonitorApp(TrafficState(listOf(device), listOf(exchange)), "Listening") }

            onNodeWithText("https://a.example/upload").performClick()
            onNodeWithText("Truncated: 0 bytes shown, original size unknown").assertExists()
            onAllNodesWithText("Truncated", substring = true).assertCountEquals(1)
            onAllNodesWithText("None").assertCountEquals(3)
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
    fun `shows the stopped status when the server is stopped by someone else`() {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val statuses = CopyOnWriteArrayList<String>()
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                runServer(server, TrafficStore(), start = {
                    server.start().also { launch { server.stop() } }
                }) { statuses += it }
            }
        }

        assertEquals("Listening on port", statuses.first().substringBeforeLast(' '))
        assertEquals(listOf("Server stopped"), statuses.drop(1))
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { AppMonitorApp(TrafficState(), statuses.last()) }

            onNodeWithText("Server stopped").assertExists()
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
    fun `reports a CancellationException thrown by the server start in an active coroutine`() {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val statuses = mutableListOf<String>()
        var stopped = false
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                runServer(
                    server,
                    TrafficStore(),
                    start = { throw CancellationException("foreign scope cancelled") },
                    stop = { stopped = true },
                ) { statuses += it }
            }
        }

        assertEquals(listOf("Server failed: foreign scope cancelled"), statuses)
        assertTrue(stopped)
    }

    @Test
    fun `a cancelled server coroutine stays cancelled`() {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val statuses = CopyOnWriteArrayList<String>()
        val started = CompletableDeferred<Unit>()
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val serving =
                    launch {
                        runServer(server, TrafficStore(), start = {
                            started.complete(Unit)
                            awaitCancellation()
                        }, stop = {}) { statuses += it }
                    }
                started.await()
                serving.cancelAndJoin()
                assertTrue(serving.isCancelled)
            }
        }

        assertEquals(emptyList(), statuses)
    }

    @Test
    fun `the exit path runs with 1 when the UI throws`() {
        val codes = mutableListOf<Int>()

        exitAfter(exit = { codes += it }) { throw IllegalStateException("window failed") }
        exitAfter(exit = { codes += it }) {}

        assertEquals(listOf(1, 0), codes)
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
    fun `reports an Error thrown by the server stop`() {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val statuses = mutableListOf<String>()
        runBlocking {
            server.stop()
            withTimeout(TIMEOUT_MS) {
                runServer(server, TrafficStore(), stop = { throw NotImplementedError("stop") }) { statuses += it }
            }
        }

        assertEquals(listOf("Server failed: Server is single use: create a new instance", "Server stop failed: stop"), statuses)
    }

    @Test
    fun `a stop that never returns is reported after the stop timeout`() {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val statuses = mutableListOf<String>()
        runBlocking {
            server.stop()
            withTimeout(TIMEOUT_MS) {
                runServer(server, TrafficStore(), stop = { awaitCancellation() }, stopTimeout = 200.milliseconds) { statuses += it }
            }
        }

        assertEquals("Server stop timed out after 200ms", statuses.last())
    }

    @Test
    fun `the app shutdown hook stops the server`() {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val port = runBlocking { server.start() }
        val hooks = CopyOnWriteArrayList<Thread>()
        addStopHook(server::stop, hooks::add)

        val hook = hooks.single().apply { start() }
        hook.join(TIMEOUT_MS)

        assertFalse(hook.isAlive)
        ServerSocket(port, 0, InetAddress.getByName("127.0.0.1")).close()
    }

    @Test
    fun `a stop that never returns delays the exit by one stop timeout and is reported once`() {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val stop = once { awaitCancellation() }
        val hooks = CopyOnWriteArrayList<Thread>()
        val hookFailures = CopyOnWriteArrayList<Throwable>()
        val statuses = CopyOnWriteArrayList<String>()
        val started = CompletableDeferred<Unit>()
        addStopHook(stop, hooks::add) { hookFailures += it }

        val begin = System.nanoTime()
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                runUntilExit(
                    serve = {
                        runServer(server, TrafficStore(), start = {
                            started.complete(Unit)
                            awaitCancellation()
                        }, stop = stop, stopTimeout = 200.milliseconds) { statuses += it }
                    },
                ) { started.await() }
            }
        }
        val hook = hooks.single().apply { start() }
        hook.join(TIMEOUT_MS)
        val elapsedMs = (System.nanoTime() - begin) / 1_000_000

        assertFalse(hook.isAlive)
        assertTrue(elapsedMs < 200 + 1_000, "exit took ${elapsedMs}ms")
        assertEquals(listOf("Server stop timed out after 200ms"), statuses)
        assertEquals(emptyList(), hookFailures)
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

    private fun ComposeUiTest.awaitText(
        text: String,
        substring: Boolean = false,
    ) = waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.screenshot(name: String) {
        val file = File(System.getProperty("screenshotDir"), name)
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
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
