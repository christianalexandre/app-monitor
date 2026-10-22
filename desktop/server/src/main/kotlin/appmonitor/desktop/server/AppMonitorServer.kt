package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.HelloAck
import io.ktor.server.application.Application
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * WebSocket server devices connect to (ADR-0002); each handshaken connection is a
 * [DeviceSession] on [sessions]. Single use: [sessions] completes on [stop] for good.
 */
class AppMonitorServer internal constructor(
    private val config: ServerConfig,
    private val handshakeTimeout: Duration = 10.seconds,
    private val createEngine: (module: Application.() -> Unit) -> EmbeddedServer<*, *> = { module ->
        embeddedServer(CIO, host = config.host, port = config.port, module = module)
    },
    private val startEngine: suspend (EmbeddedServer<*, *>) -> Unit = { it.startSuspend(wait = false) },
    private val startAdvertiser: (serviceName: String, port: Int) -> MdnsAdvertiser,
) {
    constructor(config: ServerConfig = ServerConfig()) : this(config, startAdvertiser = { name, port -> MdnsAdvertiser.start(name, port) })

    private val newSessions = Channel<DeviceSession>(Channel.UNLIMITED)

    private val lock = Any()

    // Completes with start's own release failure, if any, for a racing stop to rethrow.
    private var startup: CompletableDeferred<Throwable?>? = null

    private var stopping: Deferred<Unit>? = null

    @Volatile private var server: EmbeddedServer<*, *>? = null

    @Volatile private var advertiser: MdnsAdvertiser? = null

    @Volatile private var stopped = false

    /** Every handshaken session, once, in connection order. Meant for a single collector. */
    val sessions: Flow<DeviceSession> = newSessions.receiveAsFlow()

    /** Addresses the mDNS service is published on; empty when not advertising. */
    val advertisedAddresses: List<InetAddress> get() = advertiser?.addresses.orEmpty()

    /** Starts listening and advertising; returns the bound port. On failure, cancellation or [stop], the port and mDNS are released. */
    suspend fun start(): Int {
        val startup = CompletableDeferred<Throwable?>()
        synchronized(lock) {
            check(!stopped) { "Server is single use: create a new instance" }
            check(this.startup == null) { "Server already started" }
            this.startup = startup
        }
        var server: EmbeddedServer<*, *>? = null
        var advertiser: MdnsAdvertiser? = null
        var releaseFailure: Throwable? = null
        try {
            val engine =
                createEngine {
                    install(WebSockets)
                    routing {
                        webSocket(Protocol.WEBSOCKET_PATH) { serve() }
                    }
                }
            server = engine
            startEngine(engine)
            val port =
                engine.engine
                    .resolvedConnectors()
                    .first()
                    .port
            if (config.advertise) {
                // Assigned inside the block: a cancelled withContext drops its result.
                withContext(Dispatchers.IO) { advertiser = startAdvertiser(config.serviceName, port) }
            }
            synchronized(lock) {
                check(!stopped) { "Server stopped while starting" }
                this.server = engine
                this.advertiser = advertiser
            }
            return port
        } catch (e: Throwable) {
            // Released before startup clears: a racing stop awaits startup to see the port free.
            withContext(NonCancellable) {
                try {
                    release(advertiser, server)
                } catch (closeFailure: Throwable) {
                    releaseFailure = closeFailure
                    e.addSuppressed(closeFailure)
                }
            }
            synchronized(lock) { if (this.startup === startup) this.startup = null }
            throw e
        } finally {
            startup.complete(releaseFailure)
        }
    }

    /**
     * Waits for an in-flight [start], releases the port and completes [sessions] even if closing mDNS
     * fails, then rethrows that failure. Every call, including a later one, waits for that release and rethrows it.
     */
    suspend fun stop() {
        val releasing =
            synchronized(lock) {
                stopping ?: run {
                    stopped = true
                    val startup = this.startup
                    val server = this.server.also { this.server = null }
                    val advertiser = this.advertiser.also { this.advertiser = null }
                    // Outside the caller's job: a cancelled stop still frees the port and mDNS.
                    CoroutineScope(Dispatchers.IO)
                        .async(start = CoroutineStart.LAZY) {
                            try {
                                val startReleaseFailure = startup?.await()
                                release(advertiser, server)
                                if (startReleaseFailure != null) throw startReleaseFailure
                            } finally {
                                newSessions.close()
                            }
                        }.also { stopping = it }
                }
            }
        releasing.start()
        releasing.await()
    }

    private suspend fun release(
        advertiser: MdnsAdvertiser?,
        server: EmbeddedServer<*, *>?,
    ) {
        try {
            advertiser?.let { withContext(Dispatchers.IO) { it.close() } }
        } finally {
            server?.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    private suspend fun DefaultWebSocketServerSession.serve() {
        val first = withTimeoutOrNull(handshakeTimeout) { incoming.receiveCatching() }
        if (first == null) {
            close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "No Hello within $handshakeTimeout"))
            return
        }
        val hello = (first.getOrNull() as? Frame.Binary)?.decodeEnvelope()?.toHello()
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
        // Under the lock stop takes: a handshake racing stop is either published or gets no HelloAck.
        val published = synchronized(lock) { !stopped && newSessions.trySend(session).isSuccess }
        if (!published) {
            close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server stopped"))
            return
        }
        try {
            send(Frame.Binary(true, helloAck(session).encode()))
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
