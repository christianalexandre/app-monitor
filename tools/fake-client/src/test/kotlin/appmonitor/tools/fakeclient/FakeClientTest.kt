package appmonitor.tools.fakeclient

import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.ServerConfig
import appmonitor.protocol.Protocol
import appmonitor.v1.Hello
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FakeClientTest {
    private var server: AppMonitorServer? = null

    @AfterTest
    fun tearDown() {
        runBlocking { server?.stop() }
    }

    @Test
    fun `replays every fixture to the server`() =
        runBlocking {
            val port = start(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))

            withTimeout(TIMEOUT_MS) {
                val session = async { server!!.sessions.first() }
                val ack = replay(Target("127.0.0.1", port))

                val device = session.await()
                assertEquals(ack.session_id, device.id)
                assertEquals(Hello.ADAPTER.decode(Fixtures.hello.payload), device.hello)
                val received = device.envelopes.toList()
                assertEquals(Fixtures.traffic.map { it.copy(timestamp_ms = 0) }, received.map { it.copy(timestamp_ms = 0) })
            }
        }

    @Test
    fun `discovers the advertised server over mdns`() =
        runBlocking {
            val port = start(ServerConfig(host = "0.0.0.0", port = 0, serviceName = SERVICE_NAME))

            // Resolution takes ~6 s on a Mac while the new service is still announcing.
            val target = discover(DISCOVERY_TIMEOUT_MS, SERVICE_NAME)

            assertNotNull(target, "Server not discovered over mDNS")
            assertEquals(port, target.port)
            assertEquals(Protocol.WEBSOCKET_PATH, target.path)
        }

    private suspend fun start(config: ServerConfig): Int = AppMonitorServer(config).also { server = it }.start()

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val DISCOVERY_TIMEOUT_MS = 20_000L
        val SERVICE_NAME = "AppMonitor fake-client test ${ProcessHandle.current().pid()}"
    }
}
