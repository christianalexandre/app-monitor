package appmonitor.sdk.core

import appmonitor.desktop.server.AppMonitorServer
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
        runBlocking {
            val (server, port) = desktop()
            val client = client(port)
            val session = withTimeout(5.seconds) { server.sessions.first() }
            client.awaitState(ClientState.Connected)
            client.stopAndJoin()
            withTimeout(2.seconds) { session.envelopes.toList() }
        }

    private suspend fun desktop(): Pair<AppMonitorServer, Int> {
        val server = AppMonitorServer(ServerConfig(host = LOOPBACK, port = 0, advertise = false))
        val bound = server.start()
        cleanup += { server.stop() }
        return server to bound
    }

    private fun client(port: Int): MonitorClient {
        val config = ClientConfig(backoffInitial = 50.milliseconds, backoffMax = 200.milliseconds)
        val client = MonitorClient({ hello }, { Endpoint(LOOPBACK, port) }, config, { true }, {})
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
