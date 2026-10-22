package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.HelloAck
import appmonitor.v1.Platform
import appmonitor.v1.RequestStarted
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppMonitorServerTest {
    private val client = HttpClient(CIO) { install(WebSockets) }
    private var server: AppMonitorServer? = null

    @AfterTest
    fun tearDown() {
        client.close()
        runBlocking { server?.stop() }
    }

    @Test
    fun `handshaken device streams envelopes as a session`() =
        runBlocking {
            val port = start(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))
            val sent = (1..100).map { requestStarted("req-$it") }

            withTimeout(TIMEOUT_MS) {
                val session = async { server!!.sessions.first() }
                val ack =
                    connect(port) {
                        send(Frame.Binary(true, hello.encode()))
                        val ack = receiveEnvelope()
                        sent.forEach { send(Frame.Binary(true, it.encode())) }
                        ack
                    }

                assertEquals(Protocol.CHANNEL_CORE, ack.channel)
                assertEquals("HelloAck", ack.type)
                val helloAck = HelloAck.ADAPTER.decode(ack.payload)
                assertEquals(Protocol.VERSION, helloAck.protocol_version)
                assertEquals(listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK), helloAck.channels)

                val device = session.await()
                assertEquals(helloAck.session_id, device.id)
                assertEquals("device-0001", device.hello.device_id)
                assertEquals(sent, device.envelopes.take(100).toList())
            }
        }

    @Test
    fun `connection without hello is closed with protocol error`() =
        runBlocking {
            val port = start(ServerConfig(host = "127.0.0.1", port = 0, advertise = false))

            val reason =
                withTimeout(TIMEOUT_MS) {
                    connect(port) {
                        send(Frame.Binary(true, requestStarted("req-1").encode()))
                        closeReason.await()
                    }
                }

            assertEquals(CloseReason.Codes.PROTOCOL_ERROR.code, reason?.code)
        }

    @Test
    fun `advertised service resolves over mdns`() =
        runBlocking {
            val port = start(ServerConfig(host = "0.0.0.0", port = 0, serviceName = SERVICE_NAME))
            val address = server!!.advertisedAddresses.firstOrNull()
            assertNotNull(address, "No multicast-capable LAN address to advertise on")

            // A distinct host name: sharing the advertiser's makes the two JmDNS instances conflict.
            JmDNS.create(address, "appmonitor-test-browser").use { browser ->
                val info = withTimeout(TIMEOUT_MS) { browser.resolve(SERVICE_NAME) }
                assertEquals(port, info.port)
                assertEquals(Protocol.WEBSOCKET_PATH, info.getPropertyString("path"))
                assertTrue(info.inetAddresses.isNotEmpty())
            }
        }

    private suspend fun JmDNS.resolve(name: String): ServiceInfo {
        val resolved = CompletableDeferred<ServiceInfo>()
        addServiceListener(
            Protocol.MDNS_SERVICE_TYPE,
            object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) = requestServiceInfo(event.type, event.name)

                override fun serviceRemoved(event: ServiceEvent) = Unit

                override fun serviceResolved(event: ServiceEvent) {
                    // Fires again when the TXT record arrives after SRV/A.
                    if (event.name == name && event.info.getPropertyString("path") != null) resolved.complete(event.info)
                }
            },
        )
        return resolved.await()
    }

    private suspend fun start(config: ServerConfig): Int = AppMonitorServer(config).also { server = it }.start()

    private suspend fun <T> connect(
        port: Int,
        block: suspend DefaultClientWebSocketSession.() -> T,
    ): T {
        var result: Result<T>? = null
        client.webSocket(host = "127.0.0.1", port = port, path = Protocol.WEBSOCKET_PATH) {
            result = runCatching { block() }
        }
        return result!!.getOrThrow()
    }

    private suspend fun DefaultClientWebSocketSession.receiveEnvelope(): Envelope =
        Envelope.ADAPTER.decode((incoming.receive() as Frame.Binary).readBytes())

    private companion object {
        const val TIMEOUT_MS = 10_000L
        val SERVICE_NAME = "AppMonitor test ${ProcessHandle.current().pid()}"

        val hello =
            envelope(
                Protocol.CHANNEL_CORE,
                "Hello",
                Hello(
                    protocol_version = Protocol.VERSION,
                    platform = Platform.PLATFORM_JVM,
                    device_id = "device-0001",
                    channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK, "unknown"),
                ).encode(),
            )

        fun requestStarted(id: String) =
            envelope(Protocol.CHANNEL_NETWORK, "RequestStarted", RequestStarted(request_id = id, method = "GET").encode())

        fun envelope(
            channel: String,
            type: String,
            payload: ByteArray,
        ) = Envelope(channel = channel, type = type, payload = payload.toByteString(), timestamp_ms = 1L)
    }
}
