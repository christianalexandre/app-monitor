package appmonitor.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import appmonitor.desktop.app.store.ProxyController
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.domain.ServiceStatus
import appmonitor.desktop.feature.settings.PROXY_RESET_CA
import appmonitor.desktop.feature.settings.PROXY_STATUS
import appmonitor.desktop.feature.settings.PROXY_SWITCH
import appmonitor.desktop.proxy.ProxyRecording
import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.desktop.storage.Storage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProxyWiringTest {
    private val list = LiveTraffic()

    @Test
    fun `off by default, the switch starts it and its traffic goes to the live list`() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            try {
                val proxy = ProxyController(storage, list, dir, port = 0)
                runBlocking { proxy.start() }
                assertEquals(ServiceStatus.Off, proxy.status.value)
                assertFalse(Files.exists(proxy.caPath), "no CA before the first start")

                runBlocking { proxy.setEnabled(true) }
                val port = assertIs<ServiceStatus.Running>(proxy.status.value).port
                val origin = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                curl("--proxy", "http://127.0.0.1:$port", "http://127.0.0.1:$origin/none")
                runBlocking {
                    proxy.setEnabled(false)
                    storage.flush()
                }
                assertEquals(ServiceStatus.Off, proxy.status.value)

                assertEquals(emptyList(), runBlocking { storage.sessions(storage.runId) }, "the live list writes nothing to the store")
                val session = list.sessions().single()
                assertEquals(ProxyRecording.HELLO.device_name, session.hello.device_name)
                assertTrue(session.disconnectedAtMs != null, "a stopped proxy ends its session")
                val exchange = list.searchExchanges(ExchangeFilter(sessionId = session.id)).single()
                assertEquals("http://127.0.0.1:$origin/none", exchange.url)
                assertTrue(exchange.failed)

                val relaunched = ProxyController(storage, list, dir, port = 0)
                runBlocking { relaunched.start() }
                assertEquals(ServiceStatus.Off, relaunched.status.value, "the switch is kept off")
                runBlocking { relaunched.close() }
                runBlocking { proxy.close() }
            } finally {
                runBlocking { storage.close(kotlin.time.Duration.parse("5s")) }
            }
        }

    @Test
    fun `a taken port is a status`() =
        withStorage { storage ->
            ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { taken ->
                withDataDir { dir ->
                    val proxy = ProxyController(storage, list, dir, port = taken.localPort)
                    runBlocking { proxy.setEnabled(true) }
                    assertEquals(ServiceStatus.PortTaken(taken.localPort), proxy.status.value)
                    runBlocking { proxy.close() }
                    runBlocking { storage.flush() }
                    assertEquals(emptyList(), list.sessions(), "no session for a failed start")
                }
            }
        }

    @Test
    fun `reset and clear all replace the ca and keep the switch`() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            try {
                val proxy = ProxyController(storage, list, dir, port = 0)
                runBlocking { proxy.setEnabled(true) }
                val first = Files.readString(proxy.caPath)

                runBlocking { proxy.resetCa() }
                val second = Files.readString(proxy.caPath)
                assertNotEquals(first, second)
                assertIs<ServiceStatus.Running>(proxy.status.value)

                runBlocking {
                    storage.clearAll()
                    proxy.afterClearAll()
                }
                assertNotEquals(second, Files.readString(proxy.caPath))
                assertEquals(true.toString(), runBlocking { storage.setting(ProxyController.KEY_ENABLED) })
                assertEquals(
                    1,
                    runBlocking { withTimeout(TIMEOUT_MS) { sessionsAfterFlush() } }.size,
                    "a new session after the clear",
                )

                runBlocking { proxy.close() }
                runBlocking { proxy.setEnabled(true) }
                assertEquals(ServiceStatus.Off, proxy.status.value, "closed stays off")
            } finally {
                runBlocking { storage.close(kotlin.time.Duration.parse("5s")) }
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the settings show the proxy switch, state and launch command`() =
        withStorage { storage ->
            val proxy = FakeProxy(ServiceStatus.Running(47476), port = 47476, caFile = "/data/proxy-ca.pem")

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent {
                    AppMonitorApp(storage, "Listening", appearance = TestAppearance, proxy = proxy)
                }

                onNodeWithText("Settings").performClick()
                onNodeWithTag(
                    PROXY_STATUS,
                    useUnmergedTree = true,
                ).performScrollTo().assertTextEquals("Listening on http://127.0.0.1:47476")
                onNodeWithText(
                    "SIMCTL_CHILD_grpc_proxy=http://127.0.0.1:47476 " +
                        "SIMCTL_CHILD_GRPC_DEFAULT_SSL_ROOTS_FILE_PATH=\"/data/proxy-ca.pem\" xcrun simctl launch booted <bundle id>",
                ).assertExists()
                onNodeWithTag(PROXY_RESET_CA).performScrollTo()
                screenshot("proxy.png")
                onNodeWithTag(PROXY_RESET_CA).performClick()
                onNodeWithTag(PROXY_SWITCH).performClick()
            }

            assertEquals(listOf(false), proxy.switched)
            assertEquals(1, proxy.resets)
        }

    private suspend fun sessionsAfterFlush(): List<*> {
        while (true) {
            val sessions = list.sessions()
            if (sessions.isNotEmpty()) return sessions
            delay(20)
        }
    }

    private fun curl(vararg args: String) {
        val process = ProcessBuilder(listOf("curl", "-sS", "--max-time", "10") + args).redirectErrorStream(true).start()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "curl did not finish")
    }
}
