package appmonitor.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.store.MonitorPause
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.traffic.MONITOR_STATE
import appmonitor.desktop.feature.traffic.MONITOR_SWITCH
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.ServerConfig
import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ADR-0222: paused, the requests that start are left out whole; the ones already running end as usual.
@OptIn(ExperimentalTestApi::class)
class MonitorPauseTest {
    @Test
    fun `a request that starts while paused is left out with its later events, one already running is kept`() {
        val pause = MonitorPause()
        assertTrue(pause.admits("s1", envelope(RequestStarted("before", "GET", "https://a.example/before"))))
        pause.pause()
        assertFalse(pause.admits("s1", envelope(RequestStarted("during", "GET", "https://a.example/during"))))
        assertTrue(pause.admits("s1", envelope(ResponseReceived("before", 200))))
        pause.resume()
        assertFalse(pause.admits("s1", envelope(ResponseReceived("during", 200))), "a response after the resume")
        assertTrue(pause.admits("s1", envelope(RequestStarted("after", "GET", "https://a.example/after"))))
        assertTrue(pause.admits("s2", envelope(ResponseReceived("during", 200))), "another session's request")
        assertTrue(pause.admits("s1", Envelope(Protocol.CHANNEL_CORE, "AppName", okio.ByteString.EMPTY, 1)))
        pause.ended("s1")
        assertTrue(pause.admits("s1", envelope(ResponseReceived("during", 200))), "forgotten once the session ends")
    }

    @Test
    fun `a device's requests sent while paused never reach the live list`() =
        withStorage { storage ->
            runBlocking {
                val live = LiveTraffic()
                val pause = MonitorPause()
                val server = AppMonitorServer(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
                try {
                    val port = server.start()
                    val collecting = launch(Dispatchers.Default) { TrafficStore(storage, live, pause = pause).collect(server.sessions) }

                    suspend fun listed(count: Int) =
                        withTimeout(TIMEOUT_MS) {
                            while (live.observeExchanges(ExchangeFilter()).first().size < count) delay(20)
                            live.observeExchanges(ExchangeFilter()).first()
                        }
                    HttpClient(CIO) { install(WebSockets) }.use { client ->
                        client.webSocket(host = "127.0.0.1", port = port, path = Protocol.WEBSOCKET_PATH) {
                            suspend fun send(envelope: Envelope) = send(Frame.Binary(true, envelope.encode()))
                            val hello = HELLO.copy(channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK))
                            send(Envelope(Protocol.CHANNEL_CORE, "Hello", hello.encodeByteString(), 1))
                            incoming.receive()
                            send(envelope(RequestStarted("before", "GET", "https://a.example/before")))
                            listed(1)
                            pause.pause()
                            send(envelope(RequestStarted("during", "GET", "https://a.example/during")))
                            send(envelope(ResponseReceived("during", 200)))
                            send(envelope(ResponseReceived("before", 200)))
                            // A session's envelopes keep their order: once "before" ends, the paused ones were read.
                            withTimeout(TIMEOUT_MS) {
                                while (live.observeExchanges(ExchangeFilter()).first().none { it.statusCode == 200 }) delay(20)
                            }
                            pause.resume()
                            send(envelope(RequestStarted("after", "GET", "https://a.example/after")))
                            val rows = listed(2)
                            assertEquals(listOf("after", "before"), rows.map { it.requestId })
                            assertEquals(200, rows.last().statusCode, "the request running at the pause ends")
                        }
                    }
                    collecting.cancel()
                } finally {
                    server.stop()
                }
            }
        }

    @Test
    fun `the title bar pauses and resumes the monitor`() =
        withStorage { storage ->
            val pause = MonitorPause()
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, monitor = pause) }
                awaitText("Live")
                onNodeWithTag(MONITOR_SWITCH).performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { pause.paused.value }
                awaitText("Paused")
                onNodeWithTag(MONITOR_STATE).assertExists()
                screenshot("paused.png")
                onNodeWithTag(MONITOR_SWITCH).performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { !pause.paused.value }
                awaitText("Live")
            }
        }
}
