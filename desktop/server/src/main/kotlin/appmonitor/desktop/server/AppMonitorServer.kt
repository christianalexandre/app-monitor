package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import appmonitor.v1.DeliveryReport
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.HelloAck
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocketRaw
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * WebSocket server devices connect to (ADR-0002); each handshaken connection [approval] admits is a
 * [DeviceSession] on [sessions]. Single use: [sessions] completes on [stop] for good.
 */
class AppMonitorServer internal constructor(
    private val config: ServerConfig,
    private val handshakeTimeout: Duration = 10.seconds,
    private val pingPeriod: Duration = 15.seconds,
    private val pingTimeout: Duration = 15.seconds,
    private val createEngine: (module: Application.() -> Unit) -> EmbeddedServer<*, *> = { module ->
        embeddedServer(CIO, host = config.host, port = config.port, module = module)
    },
    private val startEngine: suspend (EmbeddedServer<*, *>) -> Unit = { it.startSuspend(wait = false) },
    private val approval: DeviceApproval = DeviceApproval.ApproveAll,
    private val startAdvertiser: (serviceName: String, port: Int) -> MdnsAdvertiser,
) {
    constructor(
        config: ServerConfig = ServerConfig(),
        approval: DeviceApproval = DeviceApproval.ApproveAll,
    ) : this(config, approval = approval, startAdvertiser = { name, port -> MdnsAdvertiser.start(name, port, config.desktopId) })

    private val newSessions = Channel<DeviceSession>(Channel.UNLIMITED)

    private val lock = Any()

    // Completes with start's own release failure, if any, for a racing stop to rethrow.
    private var startup: CompletableDeferred<Throwable?>? = null

    private var stopping: Deferred<Unit>? = null

    @Volatile private var server: EmbeddedServer<*, *>? = null

    @Volatile private var advertiser: MdnsAdvertiser? = null

    @Volatile private var stopped = false

    // From a valid Hello to the close; disconnect removes an entry so its handshake is never published.
    private val connections = ConcurrentHashMap<DeviceConnection, Hello>()

    // ADR-0166: frames read and lost so far, per closed session, for the next session's DeliveryReport.
    private val closedSessions =
        object : LinkedHashMap<String, Received>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Received>) = size > CLOSED_SESSIONS_KEPT
        }

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
                    install(WebSockets) { maxFrameSize = Protocol.MAX_FRAME_BYTES }
                    routing {
                        webSocketRaw(Protocol.WEBSOCKET_PATH, negotiateExtensions = false) {
                            val connection = DeviceConnection(this, call.request.local.remoteAddress, pingPeriod, pingTimeout, CLOSE_GRACE)
                            connection.start()
                            try {
                                connection.serve()
                            } finally {
                                connection.finish()
                            }
                        }
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

    /**
     * Closes every connection of [key] with 1008, handshakes still waiting for their answer or their `HelloAck`
     * included; the device's next `Hello` is asked for again.
     */
    fun disconnect(key: DeviceKey) {
        synchronized(lock) {
            connections.forEach { (connection, hello) ->
                if (hello.device_id == key.deviceId && hello.app_id == key.appId && connections.remove(connection, hello)) {
                    connection.launch { connection.close(FORGOTTEN) }
                }
            }
        }
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

    private suspend fun DeviceConnection.serve() {
        val first = withTimeoutOrNull(handshakeTimeout) { incoming.receiveCatching() }
        if (first == null) {
            closeAndDrain(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "No Hello within $handshakeTimeout"))
            return
        }
        val hello = (first.getOrNull() as? Frame.Binary)?.decodeEnvelope()?.toHello()
        if (hello == null) {
            closeAndDrain(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "First frame must be a Hello envelope"))
            return
        }
        if (hello.protocol_version != Protocol.VERSION) {
            closeAndDrain(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Unsupported protocol version ${hello.protocol_version}"))
            return
        }
        connections[this] = hello
        try {
            handshaken(hello)
        } finally {
            connections.remove(this)
        }
    }

    private suspend fun DeviceConnection.handshaken(hello: Hello) {
        val approved = awaitApproval(hello) ?: return
        if (!approved) {
            closeAndDrain(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Rejected on the desktop"))
            return
        }
        val session =
            DeviceSession(
                id = UUID.randomUUID().toString(),
                hello = hello,
                channels = hello.channels.filter { it in SUPPORTED_CHANNELS },
                peerAddress = peerAddress,
            )
        // Under the lock stop and disconnect take: a handshake racing either is published or gets no HelloAck.
        val publication =
            synchronized(lock) {
                when {
                    !connections.containsKey(this) -> Publication.FORGOTTEN
                    !stopped && newSessions.trySend(session).isSuccess -> Publication.PUBLISHED
                    else -> Publication.STOPPED
                }
            }
        // Returning before disconnect's close is sent lets Ktor close with 1000.
        when (publication) {
            Publication.FORGOTTEN -> {
                closeAndDrain(FORGOTTEN)
                return
            }

            Publication.STOPPED -> {
                closeAndDrain(CloseReason(CloseReason.Codes.GOING_AWAY, "Server stopped"))
                return
            }

            Publication.PUBLISHED -> {
                Unit
            }
        }
        var writer: Job? = null
        var received = 0L
        try {
            send(Frame.Binary(true, helloAck(session).encode()))
            writer = launch { for (envelope in session.outgoing) send(Frame.Binary(true, envelope.encode())) }
            for (frame in incoming) {
                received++
                val envelope = (frame as? Frame.Binary)?.decodeEnvelope()
                if (envelope == null) {
                    closeAndDrain(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Frames must be binary envelopes"))
                    break
                }
                val violation = envelope.violation(session.channels)
                if (violation != null) {
                    closeAndDrain(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, violation))
                    break
                }
                envelope.toDeliveryReport()?.let { session.report(drops(it, session.drops.value)) }
                session.deliver(envelope)
            }
        } finally {
            synchronized(closedSessions) {
                closedSessions[session.id] = Received(received, session.drops.value?.lost)
            }
            session.close()
            writer?.cancel()
        }
    }

    // The lost count is the previous session's plus the frames it wrote that were never read; set once per session.
    private fun drops(
        report: DeliveryReport,
        current: Drops?,
    ): Drops {
        val lost =
            when {
                current != null -> {
                    current.lost
                }

                report.previous_session_id.isEmpty() -> {
                    0L
                }

                else -> {
                    synchronized(closedSessions) { closedSessions[report.previous_session_id] }?.let { previous ->
                        previous.lost?.plus((report.previous_session_written - previous.frames).coerceAtLeast(0))
                    }
                }
            }
        return Drops(report.evicted, report.oversized, lost)
    }

    // Closing TCP with the device's frames unread resets it, and the device may lose this close.
    private suspend fun DeviceConnection.closeAndDrain(reason: CloseReason) {
        close(reason)
        for (frame in incoming) Unit
    }

    // No timeout: the user may take minutes. The device leaving, or sending before HelloAck, withdraws the request.
    private suspend fun DeviceConnection.awaitApproval(hello: Hello): Boolean? =
        coroutineScope {
            val decision = async { approval.approve(hello) }
            select {
                decision.onAwait { it }
                incoming.onReceiveCatching { frame ->
                    decision.cancel()
                    if (frame.isSuccess) close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Envelope before HelloAck"))
                    null
                }
            }
        }

    private fun helloAck(session: DeviceSession): Envelope =
        Envelope(
            channel = Protocol.CHANNEL_CORE,
            type = "HelloAck",
            payload = HelloAck(Protocol.VERSION, session.id, session.channels, config.desktopId.orEmpty()).encodeByteString(),
            timestamp_ms = System.currentTimeMillis(),
        )

    private enum class Publication { PUBLISHED, FORGOTTEN, STOPPED }

    private class Received(
        val frames: Long,
        val lost: Long?,
    )

    private companion object {
        val SUPPORTED_CHANNELS = setOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK, Protocol.CHANNEL_INTERACTION)
        val CLOSE_GRACE = 1.seconds
        const val CLOSED_SESSIONS_KEPT = 1024
        val FORGOTTEN = CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Forgotten on the desktop")
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

private fun Envelope.toDeliveryReport(): DeliveryReport? {
    if (channel != Protocol.CHANNEL_CORE || type != "DeliveryReport") return null
    return try {
        DeliveryReport.ADAPTER.decode(payload)
    } catch (_: IOException) {
        null
    }
}
