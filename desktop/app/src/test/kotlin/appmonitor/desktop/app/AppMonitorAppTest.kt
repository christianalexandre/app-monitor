package appmonitor.desktop.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.store.McpController
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.domain.AnsweredDevice
import appmonitor.desktop.domain.ApprovalRepository
import appmonitor.desktop.domain.PendingApproval
import appmonitor.desktop.domain.ServiceStatus
import appmonitor.desktop.feature.settings.CLEAR_ALL
import appmonitor.desktop.feature.settings.CONFIRM_CLEAR
import appmonitor.desktop.feature.settings.MCP_STATUS
import appmonitor.desktop.feature.settings.MCP_SWITCH
import appmonitor.desktop.feature.settings.claudeMcpAdd
import appmonitor.desktop.feature.traffic.RUNS
import appmonitor.desktop.feature.traffic.RUN_NAME
import appmonitor.desktop.feature.traffic.SEARCH
import appmonitor.desktop.mcp.McpAccess
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.DeviceApprovals
import appmonitor.desktop.server.DeviceKey
import appmonitor.desktop.server.ServerConfig
import appmonitor.desktop.storage.RetentionSettings
import appmonitor.protocol.Protocol
import appmonitor.tools.fakeclient.Target
import appmonitor.tools.fakeclient.replay
import appmonitor.v1.Header
import appmonitor.v1.Hello
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import appmonitor.v1.WebSocketFrame.Direction
import appmonitor.v1.WebSocketFrame.Opcode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

@OptIn(ExperimentalTestApi::class)
class AppMonitorAppTest {
    @Test
    fun `shows fake client traffic received by the server`() =
        withStorage { storage ->
            storage.receive()

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening on port 47474") }

                awaitText("Pixel 9 (emulator)")
                onNodeWithText("Android 16 · disconnected").assertExists()
                awaitText(URL)
                onNodeWithText("POST").assertExists()
                onNodeWithText("201").assertExists()
                onNodeWithText("failed").assertExists()

                onNodeWithText(URL).performClick()
                awaitText("Content-Type: application/json")
                onAllNodesWithText(URL, substring = true).assertCountEquals(2)
                onNodeWithText("Adapter layer", substring = true).assertExists()
                awaitText("{\n    \"name\": \"café\"\n}")
                awaitText("00000000  00 7f 80 ff", substring = true)
                onNodeWithText("Truncated: 4 of 2097152 bytes shown").assertExists()
                screenshot("app.png")

                onNodeWithText("req-0003").performClick()
                awaitText("WebSocket frames (1)")
                onNodeWithText("Unknown layer", substring = true).assertExists()
                onNodeWithText("↓ received · TEXT · 4 B").assertExists()
                onNodeWithText("ping").performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText("ping").fetchSemanticsNodes().size == 2 }
                screenshot("frames.png")
            }
        }

    @Test
    fun `frames of a websocket exchange show in order with their truncation`() =
        withStorage { storage ->
            fun frame(
                direction: Direction,
                opcode: Opcode,
                payload: ByteString,
                size: Long?,
                truncated: Boolean = false,
            ) = WebSocketFrame("r1", direction, opcode, payload, truncated, size)
            storage.record(
                envelope(RequestStarted(request_id = "r1", method = "GET", url = "wss://a.example/ws"), 1_000),
                envelope(ResponseReceived(request_id = "r1", status_code = 101), 1_005),
                envelope(frame(Direction.DIRECTION_SENT, Opcode.OPCODE_TEXT, "hello".encodeUtf8(), 5), 1_010),
                envelope(frame(Direction.DIRECTION_RECEIVED, Opcode.OPCODE_TEXT, "hell".encodeUtf8(), 11, truncated = true), 1_250),
                envelope(
                    frame(Direction.DIRECTION_SENT, Opcode.OPCODE_CLOSE, Buffer().writeShort(1000).writeUtf8("bye").readByteString(), null),
                    1_500,
                ),
            )

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening") }

                awaitText("wss://a.example/ws")
                onNodeWithText("wss://a.example/ws").performClick()
                awaitText("WebSocket frames (3)")
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
    fun `a body at the protocol limit renders off the UI thread`() =
        withStorage { storage ->
            val element = """{"id":12345,"name":"item","tags":["a","b"]}"""
            val json = List(Protocol.MAX_BODY_BYTES / (element.length + 1)) { element }.joinToString(",", "[", "]").encodeUtf8()
            storage.record(
                envelope(RequestStarted(request_id = "r1", method = "GET", url = "https://a.example/big")),
                envelope(
                    ResponseReceived(
                        request_id = "r1",
                        status_code = 200,
                        headers = listOf(Header("Content-Type", "application/json")),
                        body = json,
                        body_size = json.size.toLong(),
                    ),
                ),
            )

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening") }

                awaitText("https://a.example/big")
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
    fun `a pending device is admitted by clicking Approve in the dialog`() =
        withStorage { storage ->
            val approvals = DeviceApprovals()
            val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false), approvals)
            runBlocking {
                val port = server.start()
                val collecting = launch(Dispatchers.Default) { TrafficStore(storage).collect(server.sessions) }
                val replaying = async(Dispatchers.IO) { runCatching { replay(Target("127.0.0.1", port)) } }
                try {
                    runDesktopComposeUiTest(width = 1280, height = 800) {
                        setContent { AppMonitorApp(storage, "Listening", approvals = ServerApprovals(approvals)) }

                        waitUntil(timeoutMillis = TIMEOUT_MS) { approvals.pending.value.isNotEmpty() }
                        onNodeWithText("Pixel 9 (emulator) — dev.example.sample wants to connect").assertExists()
                        onNodeWithText("Pixel 9 (emulator)").assertDoesNotExist()

                        onNodeWithText("Approve").performClick()
                        waitUntil(timeoutMillis = TIMEOUT_MS) {
                            runBlocking { storage.sessions(storage.runId) }.run { isNotEmpty() && all { it.disconnectedAtMs != null } }
                        }
                        onNodeWithText("Approve").assertDoesNotExist()
                        awaitText("Pixel 9 (emulator)")
                        awaitText("201")
                    }
                    assertTrue(withTimeout(TIMEOUT_MS) { replaying.await() }.isSuccess)
                } finally {
                    collecting.cancel()
                    server.stop()
                }
            }
        }

    @Test
    fun `Reject in the dialog decides the first pending device and shows the rest waiting`() =
        withStorage { storage ->
            val approvals =
                RecordedApprovals(
                    listOf("app.a", "app.b").map {
                        PendingApproval("d1", it, Hello(device_name = "Pixel", device_id = "d1", app_id = it, os_version = "Android 16"))
                    },
                )

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", approvals = approvals) }

                onNodeWithText("Pixel — app.a wants to connect").assertExists()
                onNodeWithText("1 more waiting").assertExists()
                screenshot("approval.png")
                onNodeWithText("Reject").performClick()
            }

            assertEquals(listOf(DeviceKey("d1", "app.a") to false), approvals.decisions)
        }

    @Test
    fun `the settings list every answered pair and forget one or all`() =
        withStorage { storage ->
            val answered =
                FakeAnswered(
                    listOf(
                        AnsweredDevice("d1", "app.a", true, 1_790_000_000_000, "Pixel", "Android 16", "1.0"),
                        AnsweredDevice("d2", "app.a", false, 1_790_000_600_000, "iPhone", "iOS 26", "1.1"),
                    ),
                )
            val mcp = FakeMcp(ServiceStatus.Running(47475), port = 47475, token = "3q2-7w")

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent {
                    AppMonitorApp(
                        storage,
                        "Listening",
                        answered = answered,
                        mcp = mcp,
                    )
                }

                onNodeWithText("Settings").performClick()
                onNodeWithTag(MCP_STATUS, useUnmergedTree = true).assertTextEquals("Listening on http://127.0.0.1:47475/mcp")
                onNodeWithText(claudeMcpAdd(47475, "/mcp", "3q2-7w")).assertExists()
                onNodeWithText("Pixel — app.a").assertExists()
                onNodeWithText("iPhone — app.a").assertExists()
                screenshot("settings.png")
                onAllNodesWithText("Forget")[1].performClick()
                onNodeWithText("Forget all").performClick()
                onNodeWithTag(MCP_SWITCH).performClick()
                onNodeWithText("Close").performClick()
                onNodeWithText("Pixel — app.a").assertDoesNotExist()
            }

            assertEquals(listOf("d2" to "app.a", null), answered.forgotten)
            assertEquals(listOf(false), mcp.switched)
        }

    @Test
    fun `a taken MCP port shows a status and the rest of the app keeps running`() =
        withStorage { storage ->
            storage.record(envelope(RequestStarted(request_id = "r1", method = "GET", url = "https://a.example/login")))
            ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { taken ->
                val controller = McpController(storage, runBlocking { McpAccess.open(storage) }, taken.localPort)
                runBlocking { controller.start() }

                runDesktopComposeUiTest(width = 1280, height = 800) {
                    setContent { AppMonitorApp(storage, "Listening", mcp = controller) }

                    awaitText("Pixel")
                    onNodeWithText("Settings").performClick()
                    onNodeWithTag(MCP_STATUS, useUnmergedTree = true).assertTextContains("Port ${taken.localPort} in use", substring = true)
                    screenshot("mcp-port-taken.png")
                }
                runBlocking { controller.close() }
            }
        }

    @Test
    fun `the settings change retention and clear all data after a confirmation`() =
        withStorage { storage ->
            storage.record(envelope(RequestStarted(request_id = "r1", method = "GET", url = "https://a.example/login")))

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", onClearAll = { runBlocking { storage.clearAll() } }) }

                awaitText("Pixel")
                onNodeWithText("Settings").performClick()
                onNodeWithText("Keep: 7 days ▾").performClick()
                onNodeWithText("30 days").performClick()
                onNodeWithText("Up to: 1 GiB ▾").performClick()
                onNodeWithText("2 GiB").performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { storage.retention.value == RetentionSettings(30, 2L shl 30) }
                awaitText("Keep: 30 days ▾")
                onNodeWithText("on disk", substring = true).assertExists()

                onNodeWithTag(CLEAR_ALL).performClick()
                onNodeWithText("cannot be undone", substring = true).assertExists()
                onNodeWithText("Cancel").performClick()
                onNodeWithTag(CLEAR_ALL).performClick()
                onNodeWithTag(CONFIRM_CLEAR).performClick()
                onNodeWithText("Close").performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText("Pixel").fetchSemanticsNodes().isEmpty() }
            }

            assertEquals(RetentionSettings(), storage.retention.value, "settings cleared with the rest")
            assertEquals(emptyList(), runBlocking { storage.observeSessions().first() })
        }

    @Test
    fun `the run list switches between named runs and all of them`() =
        withStorage { storage ->
            storage.record(envelope(RequestStarted(request_id = "r1", method = "GET", url = "https://a.example/login")))
            val second = runBlocking { storage.newRun() }
            storage.record(
                envelope(RequestStarted(request_id = "r1", method = "GET", url = "https://a.example/cart")),
                sessionId = "s2",
                hello = HELLO.copy(device_id = "d2", device_name = "iPhone", os_version = "iOS 26"),
            )
            runBlocking {
                storage.rename(storage.runId, "Login on Pixel")
                storage.flush()
            }

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening") }

                awaitText("iPhone")
                onNodeWithText("Pixel").assertDoesNotExist()
                onNodeWithTag(RUNS).performClick()
                onAllNodesWithText("Rename")[0].performClick()
                onNodeWithTag(RUN_NAME).performTextReplacement("Checkout on iPhone")
                onNodeWithText("Save").performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { runBlocking { storage.runs() }.first { it.id == second }.name != null }
                awaitText("Checkout on iPhone")
                onNodeWithText("Login on Pixel").assertExists()
                onNodeWithText("current", substring = true).assertExists()
                screenshot("runs.png")

                onNodeWithText("Login on Pixel").performClick()
                awaitText("Pixel")
                onNodeWithText("iPhone").assertDoesNotExist()
                onNodeWithText("Login on Pixel ▾").assertExists()
                awaitText("https://a.example/login")

                onNodeWithTag(RUNS).performClick()
                onNodeWithText("All runs").performClick()
                awaitText("iPhone")
                onNodeWithText("Pixel").assertExists()

                onNodeWithTag(RUNS).performClick()
                onNodeWithText("New run").performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { storage.currentRun.value > second }
                // Until the new run is written, the window shows the last one with an empty first page.
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText("iPhone").fetchSemanticsNodes().isEmpty() }
                onNodeWithText("No requests yet").assertExists()
            }
        }

    @Test
    fun `the filters narrow the request list and combine`() =
        withStorage { storage ->
            fun request(
                id: String,
                method: String,
                path: String,
            ) = envelope(RequestStarted(request_id = id, method = method, url = "https://api.example.com/$path"))
            storage.record(
                request("r1", "GET", "login"),
                envelope(ResponseReceived(request_id = "r1", status_code = 200)),
                request("r2", "POST", "items"),
                envelope(ResponseReceived(request_id = "r2", status_code = 201)),
                request("r3", "GET", "items/missing"),
                envelope(ResponseReceived(request_id = "r3", status_code = 404)),
                request("r4", "GET", "feed"),
                envelope(RequestFailed(request_id = "r4", error = "timeout")),
            )
            val url = { path: String -> "https://api.example.com/$path" }

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening") }

                awaitText(url("feed"))
                onNodeWithTag(SEARCH).performTextReplacement("ITEMS")
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText(url("login")).fetchSemanticsNodes().isEmpty() }
                awaitText(url("items"))
                awaitText(url("items/missing"))
                onNodeWithText(url("feed")).assertDoesNotExist()

                onNodeWithText("Status: Any ▾").performClick()
                onNodeWithText("4xx").performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText(url("items")).fetchSemanticsNodes().isEmpty() }
                awaitText(url("items/missing"))
                onNodeWithText("Status: 4xx ▾").assertExists()
                screenshot("filters.png")

                onNodeWithText("Method: Any ▾").performClick()
                onNodeWithText("POST").performClick()
                awaitText("No requests match")

                onNodeWithTag(SEARCH).performTextReplacement("")
                onNodeWithText("Status: 4xx ▾").performClick()
                onNodeWithText("Any").performClick()
                awaitText(url("items"))
                onNodeWithText(url("login")).assertDoesNotExist()

                onNodeWithText("Method: POST ▾").performClick()
                onNodeWithText("Any").performClick()
                onNodeWithText("Failed only").performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText(url("items")).fetchSemanticsNodes().isEmpty() }
                awaitText(url("feed"))
            }
        }

    @Test
    fun `two exchanges with the same body open in their own default format`() =
        withStorage { storage ->
            val body = "{\"a\":1}".encodeUtf8()

            fun post(
                id: String,
                path: String,
                type: String,
            ) = envelope(
                RequestStarted(
                    request_id = id,
                    method = "POST",
                    url = "https://a.example/$path",
                    headers = listOf(Header("Content-Type", type)),
                    body = body,
                ),
            )
            storage.record(post("r1", "json", "application/json"), post("r2", "text", "text/plain"))

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening") }

                awaitText("https://a.example/json")
                onNodeWithText("https://a.example/json").performClick()
                awaitText("{\n    \"a\": 1\n}")

                onNodeWithText("https://a.example/text").performClick()
                awaitText("{\"a\":1}")
                onNodeWithText("{\n    \"a\": 1\n}").assertDoesNotExist()
            }
        }

    @Test
    fun `an unread body shows its truncation note and a whole one none`() =
        withStorage { storage ->
            storage.record(
                envelope(RequestStarted(request_id = "r1", method = "POST", url = "https://a.example/upload", body_truncated = true)),
                envelope(ResponseReceived(request_id = "r1", status_code = 204)),
            )

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening") }

                awaitText("https://a.example/upload")
                onNodeWithText("https://a.example/upload").performClick()
                awaitText("Truncated: 0 bytes shown, original size unknown")
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
            withTimeout(TIMEOUT_MS) { runServer(server, drain) { statuses += it } }
        }

        assertEquals(listOf("Server failed: Server is single use: create a new instance"), statuses)
        withStorage { storage ->
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, statuses.last()) }

                onNodeWithText(statuses.last()).assertExists()
            }
        }
    }

    @Test
    fun `shows the stopped status when the server is stopped by someone else`() {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val statuses = CopyOnWriteArrayList<String>()
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                runServer(server, drain, start = {
                    server.start().also { launch { server.stop() } }
                }) { statuses += it }
            }
        }

        assertEquals("Listening on port", statuses.first().substringBeforeLast(' '))
        assertEquals(listOf("Server stopped"), statuses.drop(1))
        withStorage { storage ->
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, statuses.last()) }

                onNodeWithText("Server stopped").assertExists()
            }
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
                    drain,
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
                    drain,
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
                        runServer(server, drain, start = {
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
                runServer(server, drain, stop = { throw IOException("stop failed") }) { statuses += it }
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
                runServer(server, drain, stop = { throw NotImplementedError("stop") }) { statuses += it }
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
                runServer(server, drain, stop = { awaitCancellation() }, stopTimeout = 200.milliseconds) { statuses += it }
            }
        }

        assertEquals("Server stop timed out after 200ms", statuses.last())
    }

    @Test
    fun `the app shutdown hook stops the server`() {
        val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
        val port = runBlocking { server.start() }
        val hooks = CopyOnWriteArrayList<Thread>()
        addStopHook(hooks::add) { server.stop() }

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
        addStopHook(hooks::add, { hookFailures += it }, stop = stop)

        val begin = System.nanoTime()
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                runUntilExit(
                    serve = {
                        runServer(server, drain, start = {
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
                            drain,
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
}

/** The server's pending devices as the window reads them, answered straight to it. */
private class ServerApprovals(
    private val approvals: DeviceApprovals,
) : ApprovalRepository {
    override val pending: Flow<List<PendingApproval>> =
        approvals.pending.map { list -> list.map { PendingApproval(it.key.deviceId, it.key.appId, it.hello) } }

    override suspend fun decide(
        device: PendingApproval,
        approved: Boolean,
    ) {
        approvals.decide(DeviceKey(device.deviceId, device.appId), approved)
    }
}

/** [pending] as given; every answer recorded, none applied. */
private class RecordedApprovals(
    devices: List<PendingApproval>,
) : ApprovalRepository {
    override val pending: Flow<List<PendingApproval>> = MutableStateFlow(devices)

    val decisions = mutableListOf<Pair<DeviceKey, Boolean>>()

    override suspend fun decide(
        device: PendingApproval,
        approved: Boolean,
    ) {
        decisions += DeviceKey(device.deviceId, device.appId) to approved
    }
}
