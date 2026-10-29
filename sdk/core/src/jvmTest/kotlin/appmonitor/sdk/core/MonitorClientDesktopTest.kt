package appmonitor.sdk.core

import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.DeviceApproval
import appmonitor.desktop.server.DeviceApprovals
import appmonitor.desktop.server.DeviceKey
import appmonitor.desktop.server.DeviceSession
import appmonitor.desktop.server.ServerConfig
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import appmonitor.v1.RequestStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [MonitorClient] against the real `:desktop:server`: what only the desktop decides (ADR-0066 C). */
class MonitorClientDesktopTest {
    private val hello =
        Hello(
            protocol_version = Protocol.VERSION,
            platform = Platform.PLATFORM_JVM,
            device_id = "device-1",
            app_id = "app.a",
            channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK),
        )
    private val cleanup = mutableListOf<suspend () -> Unit>()

    @AfterTest
    fun tearDown() =
        runBlocking {
            cleanup.asReversed().forEach { it() }
        }

    @Test
    fun dropsEnvelopesOnChannelsTheDesktopDidNotAccept() =
        runBlocking {
            val (server, port) = desktop()
            val client = client(port)
            val session = withTimeout(5.seconds) { server.sessions.first() }
            client.awaitState(ClientState.Connected)
            client.send(Envelope(channel = "logs", type = "Line"))
            client.send(request("kept"))
            // The desktop closes 1002 on a non-accepted channel, so "kept" arriving proves the drop.
            assertEquals(listOf("kept"), session.requestIds(1))
        }

    @Test
    fun stopWhileConnectedEndsTheDesktopSession() =
        runBlocking<Unit> {
            val (server, port) = desktop()
            val client = client(port)
            val session = withTimeout(5.seconds) { server.sessions.first() }
            client.awaitState(ClientState.Connected)
            client.stopAndJoin()
            withTimeout(2.seconds) { session.envelopes.toList() }
        }

    // ADR-0064 B.1: the Ktor plugin's sdk/core and the Swift SDK in one iOS app send one device id.
    @Test
    fun twoSessionsOfOneDeviceAndAppStayOpenTogether() =
        runBlocking {
            val approvals = DeviceApprovals()
            val (server, port) = desktop(approvals)
            val kotlin = client(port)
            val swift = client(port)
            val pending = withTimeout(5.seconds) { approvals.pending.first { it.isNotEmpty() } }
            assertEquals(listOf(DeviceKey("device-1", "app.a")), pending.map { it.key })
            approvals.decide(DeviceKey("device-1", "app.a"), approved = true)
            val sessions = withTimeout(5.seconds) { server.sessions.take(2).toList() }
            kotlin.awaitState(ClientState.Connected)
            swift.awaitState(ClientState.Connected)

            kotlin.send(request("from-kotlin"))
            swift.send(request("from-swift"))

            assertEquals(setOf("from-kotlin", "from-swift"), sessions.map { it.requestIds(1).single() }.toSet())
            assertEquals(ClientState.Connected, kotlin.state.value)
            assertEquals(ClientState.Connected, swift.state.value)
        }

    private suspend fun desktop(approval: DeviceApproval = DeviceApproval.ApproveAll): Pair<AppMonitorServer, Int> {
        val server = AppMonitorServer(ServerConfig(host = LOOPBACK, port = 0, advertise = false), approval = approval)
        val bound = server.start()
        cleanup += { server.stop() }
        return server to bound
    }

    private fun client(port: Int): MonitorClient {
        val config = ClientConfig(backoffInitial = 50.milliseconds, backoffMax = 200.milliseconds)
        val client = MonitorClient({ hello }, { Endpoint(LOOPBACK, port) }, config, {})
        cleanup += { client.stopAndJoin() }
        client.start()
        return client
    }

    private fun request(id: String) =
        Envelope(
            channel = Protocol.CHANNEL_NETWORK,
            type = "RequestStarted",
            payload = RequestStarted(request_id = id, method = "GET", url = "https://example.com/$id").encodeByteString(),
        )

    private suspend fun DeviceSession.requestIds(count: Int): List<String> =
        withTimeout(10.seconds) {
            envelopes.take(count).map { RequestStarted.ADAPTER.decode(it.payload).request_id }.toList()
        }
}
