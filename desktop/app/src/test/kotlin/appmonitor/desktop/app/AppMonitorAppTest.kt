package appmonitor.desktop.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.store.McpController
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.data.appearanceRepository
import appmonitor.desktop.data.settingRepository
import appmonitor.desktop.domain.AnsweredDevice
import appmonitor.desktop.domain.ApprovalRepository
import appmonitor.desktop.domain.DeviceConnectionRepository
import appmonitor.desktop.domain.DisconnectedDevice
import appmonitor.desktop.domain.PendingApproval
import appmonitor.desktop.domain.ServiceStatus
import appmonitor.desktop.domain.ThemeMode
import appmonitor.desktop.feature.settings.CLEAR_ALL
import appmonitor.desktop.feature.settings.CONFIRM_CLEAR
import appmonitor.desktop.feature.settings.MCP_STATUS
import appmonitor.desktop.feature.settings.MCP_SWITCH
import appmonitor.desktop.feature.settings.claudeMcpAdd
import appmonitor.desktop.feature.traffic.ALL_DEVICES
import appmonitor.desktop.feature.traffic.CLEAR
import appmonitor.desktop.feature.traffic.FAILED_ONLY
import appmonitor.desktop.feature.traffic.FILTERS
import appmonitor.desktop.feature.traffic.FILTERS_DONE
import appmonitor.desktop.feature.traffic.FILTER_RESET
import appmonitor.desktop.feature.traffic.HOST_FILTER
import appmonitor.desktop.feature.traffic.MARK
import appmonitor.desktop.feature.traffic.RECORD
import appmonitor.desktop.feature.traffic.RUNS
import appmonitor.desktop.feature.traffic.RUN_NAME
import appmonitor.desktop.feature.traffic.SAVE
import appmonitor.desktop.feature.traffic.SAVED_RUN
import appmonitor.desktop.feature.traffic.SAVE_NOTE
import appmonitor.desktop.feature.traffic.SAVE_RUN_NAME
import appmonitor.desktop.feature.traffic.SEARCH
import appmonitor.desktop.feature.traffic.STATUS_FILTER
import appmonitor.desktop.mcp.McpAccess
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.DeviceApprovals
import appmonitor.desktop.server.DeviceKey
import appmonitor.desktop.server.ServerConfig
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.desktop.storage.Recorder
import appmonitor.desktop.storage.RetentionSettings
import appmonitor.desktop.storage.Storage
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
            val live = LiveTraffic()
            live.receive(storage)

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening on port 47474", appearance = TestAppearance, live = live) }

                awaitText("Pixel 9 (emulator)")
                onNodeWithText("Android 16 · disconnected").assertExists()
                awaitText(URL)
                onNodeWithText("POST").assertExists()
                onNodeWithText("201").assertExists()
                onNodeWithText("failed").assertExists()

                onNodeWithText(URL).performClick()
                awaitText("Adapter layer", substring = true)
                onAllNodesWithText(URL, substring = true).assertCountEquals(3)
                showTab("Headers")
                awaitText("application/json")
                showTab("Body")
                awaitText("{\n    \"name\": \"café\"\n}")
                awaitText("00000000  00 7f 80 ff", substring = true)
                onNodeWithText("Truncated: 4 of 2097152 bytes shown").assertExists()
                screenshot("app.png")

                onNodeWithText("req-0003").performClick()
                awaitText("WebSocket frames (1)")
                showTab("Summary")
                awaitText("Unknown layer", substring = true)
                showTab("Body")
                onNodeWithText("↓ received · TEXT · 4 B").assertExists()
                onNodeWithText("ping").performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText("ping").fetchSemanticsNodes().size == 2 }
                screenshot("frames.png")
            }
        }

    @Test
    fun `frames of a websocket exchange show in order with their truncation`() =
        withStorage { storage ->
            val live = LiveTraffic()

            fun frame(
                direction: Direction,
                opcode: Opcode,
                payload: ByteString,
                size: Long?,
                truncated: Boolean = false,
            ) = WebSocketFrame("r1", direction, opcode, payload, truncated, size)
            live.record(
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
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }

                awaitText("wss://a.example/ws")
                onNodeWithText("wss://a.example/ws").performClick()
                showTab("Body")
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
            val live = LiveTraffic()
            val element = """{"id":12345,"name":"item","tags":["a","b"]}"""
            val json = List(Protocol.MAX_BODY_BYTES / (element.length + 1)) { element }.joinToString(",", "[", "]").encodeUtf8()
            live.record(
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
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }

                awaitText("https://a.example/big")
                val opened =
                    measureTime {
                        onNodeWithText("https://a.example/big").performClick()
                        waitForIdle()
                    }
                assertTrue(json.size in Protocol.MAX_BODY_BYTES - element.length..Protocol.MAX_BODY_BYTES, "body is ${json.size} bytes")
                assertTrue(opened < 2.seconds, "opening the exchange blocked the UI thread for $opened")
                showTab("Body")
                awaitText("[\n    {\n        \"id\": 12345,", substring = true)
            }
        }

    @Test
    fun `a pending device is admitted by clicking Approve in the dialog`() =
        withStorage { storage ->
            val live = LiveTraffic()
            val approvals = DeviceApprovals()
            val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false), approvals)
            runBlocking {
                val port = server.start()
                val collecting = launch(Dispatchers.Default) { TrafficStore(storage, live).collect(server.sessions) }
                val replaying = async(Dispatchers.IO) { runCatching { replay(Target("127.0.0.1", port)) } }
                try {
                    runDesktopComposeUiTest(width = 1280, height = 800) {
                        setContent {
                            AppMonitorApp(
                                storage,
                                "Listening",
                                appearance = TestAppearance,
                                approvals = ServerApprovals(approvals),
                                live = live,
                            )
                        }

                        waitUntil(timeoutMillis = TIMEOUT_MS) { approvals.pending.value.isNotEmpty() }
                        onNodeWithText("Pixel 9 (emulator) — dev.example.sample wants to connect").assertExists()
                        onNodeWithText("Pixel 9 (emulator)").assertDoesNotExist()

                        onNodeWithText("Approve").performClick()
                        waitUntil(timeoutMillis = TIMEOUT_MS) {
                            live.sessions().run { isNotEmpty() && all { it.disconnectedAtMs != null } }
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
    fun `Disconnect refuses a device until Reconnect`() =
        withStorage { storage ->
            val live = LiveTraffic()
            runBlocking { live.connected("s1", HELLO, "127.0.0.1") }
            val connections =
                object : DeviceConnectionRepository {
                    override val disconnected = MutableStateFlow(emptyList<DisconnectedDevice>())

                    override suspend fun disconnect(hello: Hello) {
                        disconnected.value += DisconnectedDevice(hello.device_id, hello.app_id, hello)
                        live.disconnected("s1")
                    }

                    override suspend fun reconnect(
                        deviceId: String,
                        appId: String,
                    ) {
                        disconnected.value = emptyList()
                    }
                }

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, connections = connections, live = live) }

                awaitText("Android 16 · connected")
                onNodeWithText("Disconnect").performClick()
                awaitText("Disconnected")
                onNodeWithText("Disconnect").assertDoesNotExist()
                onNodeWithText("Pixel").assertExists()
                screenshot("disconnected.png")

                onNodeWithText("Reconnect").performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText("Reconnect").fetchSemanticsNodes().isEmpty() }
                onNodeWithText("Pixel").assertDoesNotExist()
            }
            assertEquals(emptyList(), connections.disconnected.value)
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
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, approvals = approvals) }

                onNodeWithText("Pixel — app.a wants to connect").assertExists()
                onNodeWithText("1 more waiting").assertExists()
                screenshot("approval.png")
                onNodeWithText("Reject").performClick()
            }

            assertEquals(listOf(DeviceKey("d1", "app.a") to false), approvals.decisions)
        }

    @Test
    fun `the theme chosen in the settings repaints the window and is stored`() =
        withStorage { storage ->
            val appearance = runBlocking { storage.settingRepository().appearanceRepository() }
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = appearance) }
                runBlocking { appearance.setTheme(ThemeMode.Light) }
                waitForIdle()
                assertTrue(cornerLuminance() > 0.9, "light window")

                onNodeWithText("Settings").performClick()
                onNodeWithText("Theme: Light ▾").performClick()
                onNodeWithText("Dark").performClick()
                onNodeWithText("Close").performClick()
                waitForIdle()

                assertEquals(ThemeMode.Dark, appearance.theme.value)
                assertTrue(cornerLuminance() < 0.2, "dark window")
            }
            runBlocking {
                storage.flush()
                assertEquals(
                    ThemeMode.Dark,
                    storage
                        .settingRepository()
                        .appearanceRepository()
                        .theme.value,
                )
            }
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
                        appearance = TestAppearance,
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
            val live = LiveTraffic()
            live.record(envelope(RequestStarted(request_id = "r1", method = "GET", url = "https://a.example/login")))
            ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { taken ->
                val controller = McpController(storage, live, runBlocking { McpAccess.open(storage.settingRepository()) }, taken.localPort)
                runBlocking { controller.start() }

                runDesktopComposeUiTest(width = 1280, height = 800) {
                    setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live, mcp = controller) }

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
            val live = LiveTraffic()
            live.record(envelope(RequestStarted(request_id = "r1", method = "GET", url = "https://a.example/login")))

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent {
                    AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live, onClearAll = {
                        runBlocking { storage.clearAll() }
                        live.clear()
                    })
                }

                awaitText("Pixel")
                onNodeWithText("Settings").performClick()
                onNodeWithText("Keep: 7 days ▾").performClick()
                awaitText("30 days")
                onNodeWithText("30 days").performClick()
                onNodeWithText("Up to: 1 GiB ▾").performClick()
                awaitText("2 GiB")
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
            assertEquals(emptyList(), live.sessions())
        }

    @Test
    fun `the window opens on the live list and the run list shows stored runs read-only`() =
        withStorage { storage ->
            val live = LiveTraffic()
            live.record(
                envelope(RequestStarted(request_id = "r1", method = "GET", url = "https://a.example/home")),
                hello = HELLO.copy(device_id = "d3", device_name = "Galaxy"),
            )
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
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }

                awaitText("Galaxy")
                onNodeWithText("Live ▾").assertExists()
                onNodeWithText("iPhone").assertDoesNotExist()
                onNodeWithText("Pixel").assertDoesNotExist()
                onNodeWithTag(RUNS).performClick()
                onAllNodesWithText("Rename")[0].performClick()
                onNodeWithTag(RUN_NAME).performTextReplacement("Checkout on iPhone")
                onNodeWithTag(SAVE_RUN_NAME).performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { runBlocking { storage.runs() }.first { it.id == second }.name != null }
                awaitText("Checkout on iPhone")
                onNodeWithText("Login on Pixel").assertExists()
                screenshot("runs.png")

                onNodeWithText("Login on Pixel").performClick()
                awaitText("Pixel")
                onNodeWithText("iPhone").assertDoesNotExist()
                onNodeWithText("Login on Pixel ▾").assertExists()
                awaitText("https://a.example/login")

                onNodeWithTag(CLEAR).assertDoesNotExist()

                onNodeWithTag(RUNS).performClick()
                onNodeWithText("New run").assertDoesNotExist()
                onNodeWithText("Live").performClick()
                awaitText("Galaxy")
                onNodeWithText("Pixel").assertDoesNotExist()
            }
        }

    @Test
    fun `two devices share the live list and Clear empties it`() =
        withStorage { storage ->
            val live = LiveTraffic()
            live.record(envelope(RequestStarted(request_id = "r1", method = "GET", url = "https://a.example/login")))
            live.record(
                envelope(RequestStarted(request_id = "r1", method = "GET", url = "https://a.example/cart")),
                sessionId = "s2",
                hello = HELLO.copy(device_id = "d2", device_name = "iPhone", os_version = "iOS 26"),
            )

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }

                awaitText("https://a.example/login")
                onNodeWithText("https://a.example/cart").assertExists()
                onNodeWithText("iPhone").performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText("https://a.example/login").fetchSemanticsNodes().isEmpty() }
                onNodeWithTag(ALL_DEVICES).performClick()
                awaitText("https://a.example/login")
                screenshot("live.png")

                onNodeWithTag(CLEAR).performClick()
                awaitText("No requests yet")
                onNodeWithText("Requests of every connected device show here as they happen").assertExists()
                onNodeWithText("https://a.example/cart").assertDoesNotExist()
                screenshot("cleared.png")
            }
            assertEquals(emptyList(), runBlocking { storage.allExchanges() }, "the live list writes nothing to the store")
        }

    @Test
    fun `Record stores what starts until Stop, from a device connected before it`() =
        withDataDir { dir ->
            val storage = Storage.open(dir, startRun = false)
            val live = LiveTraffic()
            val recorder = Recorder(live, storage)

            fun request(
                id: String,
                path: String,
            ) = envelope(RequestStarted(request_id = id, method = "GET", url = "https://a.example/$path"))
            runBlocking {
                recorder.connected("s1", HELLO, "127.0.0.1")
                recorder.received("s1", request("r1", "before"))
            }

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }

                awaitText("https://a.example/before")
                onNodeWithTag(RECORD).performClick()
                awaitText("Stop recording")
                runBlocking {
                    recorder.received("s1", request("r2", "during"))
                    recorder.connected("s2", HELLO.copy(device_id = "d2", device_name = "iPhone"), null)
                    recorder.received("s2", request("r1", "other"))
                }
                awaitText("https://a.example/other")
                screenshot("recording.png")
                onNodeWithTag(RECORD).performClick()
                awaitText("Record")
                runBlocking {
                    recorder.received("s1", request("r3", "after"))
                    storage.flush()
                }
                awaitText("https://a.example/after")

                onNodeWithTag(RUNS).performClick()
                onAllNodesWithText("Rename").assertCountEquals(1)
                onNodeWithText("Live").assertExists()
                onAllNodesWithText("Rename")[0].performClick()
                onNodeWithTag(RUN_NAME).performTextReplacement("Checkout")
                onNodeWithTag(SAVE_RUN_NAME).performClick()
                awaitText("Checkout")
                onNodeWithText("Checkout").performClick()
                awaitText("https://a.example/during")
                onNodeWithText("https://a.example/other").assertExists()
                onNodeWithText("https://a.example/before").assertDoesNotExist()
                onNodeWithText("https://a.example/after").assertDoesNotExist()
            }
            val run = runBlocking { storage.runs() }.single()
            assertEquals(
                listOf("during", "other"),
                runBlocking { storage.allExchanges(run.id) }.map { it.url!!.substringAfterLast('/') },
            )
            runBlocking { storage.close(5.seconds) }
            Unit
        }

    @Test
    fun `Save keeps the picked live requests in Saved, listed apart and read-only`() =
        withStorage { storage ->
            val live = LiveTraffic()
            live.record(
                envelope(RequestStarted(request_id = "r1", method = "GET", url = "https://a.example/keep")),
                envelope(RequestStarted(request_id = "r2", method = "GET", url = "https://a.example/also")),
                envelope(RequestStarted(request_id = "r3", method = "GET", url = "https://a.example/drop")),
            )

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }

                awaitText("https://a.example/keep")
                onNodeWithTag(MARK + "r1").performClick()
                onNodeWithTag(MARK + "r2").performClick()
                onNodeWithText("Save 2").performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithTag(SAVE_NOTE).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Saved 2 to Saved").assertExists()
                screenshot("saved.png")

                onNodeWithTag(RUNS).performClick()
                onNodeWithTag(SAVED_RUN).performClick()
                awaitText("Saved ▾")
                awaitText("https://a.example/also")
                onNodeWithText("https://a.example/drop").assertDoesNotExist()
                onNodeWithTag(SAVE).assertDoesNotExist()
                onNodeWithTag(CLEAR).assertDoesNotExist()
            }
            val saved = runBlocking { storage.runs() }.single { it.saved }
            assertEquals(
                listOf("keep", "also"),
                runBlocking { storage.allExchanges(saved.id) }.map { it.url!!.substringAfterLast('/') },
            )
        }

    @Test
    fun `the filter sheet narrows the request list by status class, host and method, and combines`() =
        withStorage { storage ->
            val live = LiveTraffic()

            fun request(
                id: String,
                method: String,
                url: String,
            ) = envelope(RequestStarted(request_id = id, method = method, url = url))
            live.record(
                request("r1", "GET", "https://api.example.com/login"),
                envelope(ResponseReceived(request_id = "r1", status_code = 200)),
                request("r2", "POST", "https://shop.example.com/items"),
                envelope(ResponseReceived(request_id = "r2", status_code = 201)),
                request("r3", "GET", "https://api.example.com/items/missing"),
                envelope(ResponseReceived(request_id = "r3", status_code = 404)),
                request("r4", "GET", "https://api.example.com/feed"),
                envelope(RequestFailed(request_id = "r4", error = "timeout")),
            )
            val api = { path: String -> "https://api.example.com/$path" }
            val shop = "https://shop.example.com/items"

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }

                awaitText(api("feed"))
                onNodeWithTag(SEARCH).performTextReplacement("ITEMS")
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText(api("login")).fetchSemanticsNodes().isEmpty() }
                awaitText(shop)
                awaitText(api("items/missing"))
                onNodeWithText(api("feed")).assertDoesNotExist()

                onNodeWithTag(FILTERS).performClick()
                onNodeWithTag(HOST_FILTER + "api.example.com").assertExists()
                onNodeWithTag(HOST_FILTER + "shop.example.com").assertExists()
                onNodeWithTag(STATUS_FILTER + 400).performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText(shop).fetchSemanticsNodes().isEmpty() }
                awaitText(api("items/missing"))
                onNodeWithTag(STATUS_FILTER + 400).assertIsOn()
                screenshot("filters.png")

                onNodeWithText("Method: Any ▾").performClick()
                onNodeWithText("POST").performClick()
                onNodeWithTag(FILTERS_DONE).performClick()
                awaitText("No requests match")
                onNodeWithText("Change the search or the filters").assertExists()
                onNodeWithText("Filters (2)").assertExists()

                onNodeWithTag(FILTERS).performClick()
                onNodeWithTag(FILTER_RESET).performClick()
                onNodeWithTag(HOST_FILTER + "shop.example.com").performClick()
                onNodeWithTag(FILTERS_DONE).performClick()
                onNodeWithTag(SEARCH).performTextReplacement("")
                awaitText(shop)
                onNodeWithText(api("items/missing")).assertDoesNotExist()
                onNodeWithText(api("login")).assertDoesNotExist()

                onNodeWithTag(FILTERS).performClick()
                onNodeWithTag(HOST_FILTER + "shop.example.com").performClick()
                onNodeWithTag(FAILED_ONLY).performClick()
                onNodeWithTag(FILTERS_DONE).performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText(shop).fetchSemanticsNodes().isEmpty() }
                awaitText(api("feed"))
                onNodeWithText("Filters (1)").assertExists()
            }
        }

    @Test
    fun `two exchanges with the same body open in their own default format`() =
        withStorage { storage ->
            val live = LiveTraffic()
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
            live.record(post("r1", "json", "application/json"), post("r2", "text", "text/plain"))

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }

                awaitText("https://a.example/json")
                onNodeWithText("https://a.example/json").performClick()
                showTab("Body")
                awaitText("{\n    \"a\": 1\n}")

                onNodeWithText("https://a.example/text").performClick()
                awaitText("{\"a\":1}")
                onNodeWithText("{\n    \"a\": 1\n}").assertDoesNotExist()
            }
        }

    @Test
    fun `an unread body shows its truncation note and a whole one none`() =
        withStorage { storage ->
            val live = LiveTraffic()
            live.record(
                envelope(RequestStarted(request_id = "r1", method = "POST", url = "https://a.example/upload", body_truncated = true)),
                envelope(ResponseReceived(request_id = "r1", status_code = 204)),
            )

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }

                awaitText("https://a.example/upload")
                onNodeWithText("https://a.example/upload").performClick()
                showTab("Headers")
                awaitText("None")
                onAllNodesWithText("None").assertCountEquals(2)
                showTab("Body")
                awaitText("Truncated: 0 bytes shown, original size unknown")
                onAllNodesWithText("Truncated", substring = true).assertCountEquals(1)
                onAllNodesWithText("None").assertCountEquals(1)
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
