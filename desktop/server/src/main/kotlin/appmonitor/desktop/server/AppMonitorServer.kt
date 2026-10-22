package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.HelloAck
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.util.UUID

/**
 * WebSocket server devices connect to (ADR-0002). Each connection that completes the
 * `Hello` / `HelloAck` handshake becomes a [DeviceSession] on [sessions]. Single use:
 * after [stop], [sessions] completes and the server cannot start again.
 */
class AppMonitorServer internal constructor(
    private val config: ServerConfig,
    private val startAdvertiser: (serviceName: String, port: Int) -> MdnsAdvertiser,
) {
    constructor(config: ServerConfig = ServerConfig()) : this(config, { name, port -> MdnsAdvertiser.start(name, port) })

    private val newSessions = Channel<DeviceSession>(Channel.UNLIMITED)
    private var server: EmbeddedServer<*, *>? = null
    private var advertiser: MdnsAdvertiser? = null

    /** Every handshaken session, once, in connection order. Meant for a single collector. */
    val sessions: Flow<DeviceSession> = newSessions.receiveAsFlow()

    /** Addresses the mDNS service is published on; empty when not advertising. */
    val advertisedAddresses: List<InetAddress> get() = advertiser?.addresses.orEmpty()

    /** Starts listening and advertising; returns the bound port. If advertising fails, the port is released. */
    suspend fun start(): Int {
        check(server == null) { "Server already started" }
        val server =
            embeddedServer(CIO, host = config.host, port = config.port) {
                install(WebSockets)
                routing {
                    webSocket(Protocol.WEBSOCKET_PATH) { serve() }
                }
            }
        server.startSuspend(wait = false)
        this.server = server
        val port =
            server.engine
                .resolvedConnectors()
                .first()
                .port
        if (config.advertise) {
            advertiser =
                try {
                    withContext(Dispatchers.IO) { startAdvertiser(config.serviceName, port) }
                } catch (e: Exception) {
                    server.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000)
                    this.server = null
                    throw e
                }
        }
        return port
    }

    suspend fun stop() {
        advertiser?.let { withContext(Dispatchers.IO) { it.close() } }
        advertiser = null
        server?.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000)
        server = null
        newSessions.close()
    }

    private suspend fun DefaultWebSocketServerSession.serve() {
        val hello = (incoming.receiveCatching().getOrNull() as? Frame.Binary)?.decodeEnvelope()?.toHello()
        if (hello == null) {
            close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "First frame must be a Hello envelope"))
            return
        }
        if (hello.protocol_version != Protocol.VERSION) {
            close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Unsupported protocol version ${hello.protocol_version}"))
            return
        }
        val session =
            DeviceSession(
                id = UUID.randomUUID().toString(),
                hello = hello,
                channels = hello.channels.filter { it in SUPPORTED_CHANNELS },
            )
        send(Frame.Binary(true, helloAck(session).encode()))
        newSessions.send(session)
        try {
            for (frame in incoming) {
                val envelope = (frame as? Frame.Binary)?.decodeEnvelope()
                if (envelope == null) {
                    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Frames must be binary envelopes"))
                    break
                }
                session.deliver(envelope)
            }
        } finally {
            session.close()
        }
    }

    private fun helloAck(session: DeviceSession): Envelope =
        Envelope(
            channel = Protocol.CHANNEL_CORE,
            type = "HelloAck",
            payload = HelloAck(Protocol.VERSION, session.id, session.channels).encodeByteString(),
            timestamp_ms = System.currentTimeMillis(),
        )

    private companion object {
        val SUPPORTED_CHANNELS = setOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK)
    }
}

private fun Frame.Binary.decodeEnvelope(): Envelope? =
    try {
        Envelope.ADAPTER.decode(data)
    } catch (_: IOException) {
        null
    }

private fun Envelope.toHello(): Hello? {
    if (channel != Protocol.CHANNEL_CORE || type != "Hello") return null
    return try {
        Hello.ADAPTER.decode(payload)
    } catch (_: IOException) {
        null
    }
}
