package appmonitor.sdk.core

import appmonitor.protocol.Protocol
import appmonitor.v1.BreakpointAbort
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointResume
import appmonitor.v1.BreakpointRules
import appmonitor.v1.DeliveryReport
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.HelloAck
import appmonitor.v1.MockRules
import appmonitor.v1.ReplayRefused
import appmonitor.v1.ReplayRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where a desktop listens; [checksPin] false for a manual host, which neither checks nor pins (ADR-0168). */
internal data class Endpoint(
    val host: String,
    val port: Int = Protocol.DEFAULT_PORT,
    val path: String = Protocol.WEBSOCKET_PATH,
    val checksPin: Boolean = true,
)

/** The states of SDD 04; [Idle] until [MonitorClient.start]. */
internal enum class ClientState { Idle, Discovering, Connecting, Handshaking, Connected, BackingOff, Stopped }

/** Bounds of SDD 04; tests shorten them. */
internal data class ClientConfig(
    val discoveryTimeout: Duration = 5.seconds,
    val connectTimeout: Duration = 10.seconds,
    val pingInterval: Duration = 15.seconds,
    val closeTimeout: Duration = 1.seconds,
    val backoffInitial: Duration = 1.seconds,
    val backoffMax: Duration = 30.seconds,
    val bufferCount: Int = EventBuffer.MAX_COUNT,
    val bufferBytes: Long = EventBuffer.MAX_BYTES,
)

/**
 * One connection to the desktop as the state machine of SDD 04 (ADR-0022). Single use:
 * [start] once, [stop] once; [send] is safe from any thread and never blocks.
 */
internal class MonitorClient(
    hello: () -> Hello,
    private val discover: suspend () -> Endpoint?,
    private val config: ClientConfig = ClientConfig(),
    private val log: (String) -> Unit = ::platformLog,
    random: Random = Random.Default,
    private val pin: DesktopPin? = null,
    private val openSession: suspend HttpClient.(Endpoint, suspend DefaultClientWebSocketSession.() -> Unit) -> Unit =
        { endpoint, block -> webSocket(host = endpoint.host, port = endpoint.port, path = endpoint.path, block = block) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val buffer = EventBuffer(config.bufferCount, config.bufferBytes)
    private val backoff = Backoff(config.backoffInitial, config.backoffMax, random = random)
    private val stopRequested = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(ClientState.Idle)

    // Built on the client's scope, off the caller's thread: it may read storage.
    private val hello by lazy(hello)

    private val lock = SynchronizedObject()
    private var client: HttpClient? = null
    private var running: Job? = null
    private var releasing: Job? = null

    // Under lock with stopRequested: stop must not cancel a connect or a session (ADR-0067 C).
    private var inConnect = false

    val state: StateFlow<ClientState> = mutableState.asStateFlow()

    /** The desktop's mock rules while a session that accepted `interaction` is up. */
    val mocks: MockRuleSet = MockRuleSet()

    /** The desktop's breakpoints and the calls they hold, while a session that accepted `interaction` is up. */
    val breakpoints: Breakpoints = Breakpoints()

    /** The requests a `ReplayRequest` can name, kept by the capture modules; emptied when a session ends. */
    val replays: RecentRequests = RecentRequests()

    /** Envelopes dropped by the buffer or for their size, reported to the desktop (ADR-0166). */
    val dropped: Long get() = buffer.dropped + synchronized(lock) { oversized }

    private var oversized = 0L

    // The last session's HelloAck id and frames written after Hello on it (ADR-0166); under lock.
    private var previousSession: Pair<String, Long>? = null

    private var connectFailureLogged = false

    private var pinRefusalLogged = false

    fun start() {
        synchronized(lock) {
            check(running == null && !stopRequested.isCompleted) { "MonitorClient is single use" }
            client =
                HttpClient(CIO) {
                    install(WebSockets) {
                        pingIntervalMillis = config.pingInterval.inWholeMilliseconds
                        maxFrameSize = Protocol.MAX_FRAME_BYTES
                    }
                }
            running = scope.launch { run() }
        }
    }

    /** Buffers [envelope] for the desktop; dropped when stopped or larger than a frame (ADR-0022), then [onDropped]. */
    fun send(
        envelope: Envelope,
        onDropped: (() -> Unit)? = null,
    ) {
        val bytes = if (stopRequested.isCompleted) null else envelope.encode()
        if (bytes == null || bytes.size > Protocol.MAX_FRAME_BYTES) {
            if (bytes != null) synchronized(lock) { oversized++ }
            onDropped?.invoke()
            return
        }
        buffer.offer(BufferedFrame(envelope.channel, bytes, onDropped))
    }

    /** Returns at once; the release runs on the client's scope, bounded by [ClientConfig.closeTimeout]. */
    fun stop() {
        synchronized(lock) {
            if (!stopRequested.complete(Unit)) return
            val run = running
            if (!inConnect) run?.cancel()
            releasing =
                scope
                    .launch {
                        if (run != null && withTimeoutOrNull(config.closeTimeout) { run.join() } == null) run.cancelAndJoin()
                        client?.close()
                        buffer.clear()
                        mutableState.value = ClientState.Stopped
                    }.also { it.invokeOnCompletion { scope.cancel() } }
        }
    }

    /** Waits for the release started by [stop]. */
    suspend fun join() {
        synchronized(lock) { releasing }?.join()
    }

    private suspend fun run() {
        while (true) {
            mutableState.value = ClientState.Discovering
            val endpoint = withTimeoutOrNull(config.discoveryTimeout) { discover() }
            if (endpoint != null) {
                when (connect(endpoint)) {
                    Outcome.Stopped -> return
                    Outcome.Rejected -> backoff.exhaust()
                    Outcome.Lost -> if (stopRequested.isCompleted) return
                }
            }
            mutableState.value = ClientState.BackingOff
            delay(backoff.nextDelay())
        }
    }

    private suspend fun connect(endpoint: Endpoint): Outcome {
        synchronized(lock) {
            if (stopRequested.isCompleted) return Outcome.Stopped
            inConnect = true
        }
        try {
            return connectAndServe(endpoint)
        } finally {
            synchronized(lock) { inConnect = false }
        }
    }

    private suspend fun connectAndServe(endpoint: Endpoint): Outcome {
        mutableState.value = ClientState.Connecting
        val http = checkNotNull(client)
        val upgraded = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<Outcome>()
        return try {
            coroutineScope {
                val watchdog =
                    launch {
                        withTimeoutOrNull(config.connectTimeout) { upgraded.await() }
                            ?: throw IOException("No WebSocket upgrade within ${config.connectTimeout}")
                    }
                val call =
                    launch {
                        http.openSession(endpoint) {
                            upgraded.complete(Unit)
                            outcome.complete(session(this, endpoint))
                        }
                    }
                select {
                    call.onJoin { }
                    outcome.onAwait { }
                }
                // Without the bound, a desktop that stopped answering keeps this call open forever (ADR-0067 D).
                if (withTimeoutOrNull(config.closeTimeout) { call.join() } == null) call.cancel()
                watchdog.cancel()
                if (outcome.isCompleted) outcome.await() else Outcome.Lost
            }
        } catch (e: CancellationException) {
            // Ktor cancels the session's channels when the connection drops.
            currentCoroutineContext().ensureActive()
            settled(outcome)
        } catch (e: Exception) {
            if (!upgraded.isCompleted && !connectFailureLogged) {
                connectFailureLogged = true
                log(
                    "cannot reach the desktop at ${endpoint.host}:${endpoint.port} (${e.message ?: e::class.simpleName}); " +
                        "retrying with backoff. $CONNECT_FAILURE_HINT",
                )
            }
            settled(outcome)
        }
    }

    /** The session's outcome when it ended before the call's teardown failed, else [Outcome.Lost]. */
    private suspend fun settled(outcome: CompletableDeferred<Outcome>): Outcome = if (outcome.isCompleted) outcome.await() else Outcome.Lost

    private suspend fun session(
        ws: DefaultClientWebSocketSession,
        endpoint: Endpoint,
    ): Outcome {
        if (stopRequested.isCompleted) return closeNormally(ws)
        mutableState.value = ClientState.Handshaking
        return coroutineScope {
            val stop = async { stopRequested.await() }
            val serve = async { serveUntilClosed(ws, endpoint) }
            select {
                serve.onAwait { outcome ->
                    stop.cancel()
                    outcome
                }
                stop.onAwait {
                    serve.cancel()
                    closeNormally(ws)
                }
            }
        }
    }

    /** [serve], and the desktop's close when a write fails: a 1008 can arrive before `Hello` is written. */
    private suspend fun serveUntilClosed(
        ws: DefaultClientWebSocketSession,
        endpoint: Endpoint,
    ): Outcome =
        try {
            serve(ws, endpoint)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            closeOutcome(ws)
        } catch (e: Exception) {
            closeOutcome(ws)
        }

    private suspend fun serve(
        ws: DefaultClientWebSocketSession,
        endpoint: Endpoint,
    ): Outcome {
        ws.send(Frame.Binary(true, helloEnvelope().encode()))
        val ack = awaitHelloAck(ws) ?: return closeOutcome(ws)
        if (endpoint.checksPin && !trusts(ack.desktop_id)) {
            if (!pinRefusalLogged) {
                pinRefusalLogged = true
                log(
                    "the desktop at ${endpoint.host}:${endpoint.port} is not the one this install paired with; " +
                        "clear the app's data, or set AppMonitorOptions.host",
                )
            }
            ws.close(CloseReason(CloseReason.Codes.NORMAL, PIN_REFUSAL))
            return Outcome.Rejected
        }
        backoff.reset()
        connectFailureLogged = false
        mutableState.value = ClientState.Connected
        val accepted = ack.channels.toSet()
        val reports = Protocol.CHANNEL_CORE in accepted
        var written = 0L
        try {
            coroutineScope {
                val sender =
                    launch {
                        var reported: Pair<Long, Long>? = null
                        while (true) {
                            val drops = buffer.dropped to synchronized(lock) { oversized }
                            if (reports && drops != reported) {
                                ws.send(Frame.Binary(true, deliveryReport(drops).encode()))
                                synchronized(lock) { written++ }
                                ws.flush()
                                reported = drops
                                continue
                            }
                            val frame = buffer.take()
                            if (frame.channel in accepted) {
                                ws.send(Frame.Binary(true, frame.bytes))
                                synchronized(lock) { written++ }
                                ws.flush()
                            } else {
                                frame.onDropped?.invoke()
                            }
                        }
                    }
                try {
                    for (frame in ws.incoming) receive(frame, accepted)
                } finally {
                    mocks.clear()
                    breakpoints.clear()
                    replays.clear()
                }
                sender.cancel()
            }
        } finally {
            synchronized(lock) { previousSession = ack.session_id to written }
        }
        return closeOutcome(ws)
    }

    /** ADR-0166: counts from this client's start, and the frames written on its previous session. */
    private fun deliveryReport(drops: Pair<Long, Long>): Envelope {
        val previous = synchronized(lock) { previousSession }
        return Envelope(
            channel = Protocol.CHANNEL_CORE,
            type = "DeliveryReport",
            payload =
                DeliveryReport(
                    evicted = drops.first,
                    oversized = drops.second,
                    previous_session_id = previous?.first.orEmpty(),
                    previous_session_written = previous?.second ?: 0L,
                ).encodeByteString(),
            timestamp_ms = Clock.System.now().toEpochMilliseconds(),
        )
    }

    /** A desktop's envelope on an accepted channel; one this SDK does not know, or cannot decode, is ignored. */
    private fun receive(
        frame: Frame,
        accepted: Set<String>,
    ) {
        val envelope = (frame as? Frame.Binary)?.let { runCatching { Envelope.ADAPTER.decode(it.readBytes()) }.getOrNull() } ?: return
        if (envelope.channel !in accepted) return
        if (envelope.channel != Protocol.CHANNEL_INTERACTION) return
        when (envelope.type) {
            "MockRules" -> {
                runCatching { MockRules.ADAPTER.decode(envelope.payload) }.onSuccess { mocks.replace(it.rules) }
            }

            "BreakpointRules" -> {
                runCatching {
                    BreakpointRules.ADAPTER.decode(
                        envelope.payload,
                    )
                }.onSuccess { breakpoints.replace(it.rules) }
            }

            "BreakpointResume" -> {
                runCatching { BreakpointResume.ADAPTER.decode(envelope.payload) }.onSuccess { breakpoints.resume(it) }
            }

            "BreakpointAbort" -> {
                runCatching { BreakpointAbort.ADAPTER.decode(envelope.payload) }.onSuccess { breakpoints.abort(it) }
            }

            "ReplayRequest" -> {
                runCatching { ReplayRequest.ADAPTER.decode(envelope.payload) }.onSuccess { replay(it.request_id) }
            }
        }
    }

    /** Sends [hit] and waits for the desktop's answer (ADR-0111 A.1, B.1). */
    @OptIn(InternalAppMonitorApi::class)
    suspend fun hold(hit: BreakpointHit): BreakpointOutcome =
        breakpoints.hold(hit) { held, dropped ->
            send(
                Envelope(
                    channel = Protocol.CHANNEL_INTERACTION,
                    type = "BreakpointHit",
                    payload = held.encodeByteString(),
                    timestamp_ms = Clock.System.now().toEpochMilliseconds(),
                ),
                dropped,
            )
        }

    /** ADR-0120 A.1: a request not sent again is answered with `ReplayRefused`. */
    private fun replay(requestId: String) {
        val reason = replays.replay(requestId) ?: return
        send(
            Envelope(
                channel = Protocol.CHANNEL_INTERACTION,
                type = "ReplayRefused",
                payload = ReplayRefused(request_id = requestId, reason = reason).encodeByteString(),
                timestamp_ms = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    /** ADR-0168: the pinned desktop only; the first one with an id is pinned. */
    private fun trusts(desktopId: String): Boolean {
        val pin = pin ?: return true
        val pinned = pin.pinned() ?: return desktopId.isEmpty() || pin.pin(desktopId) == desktopId
        return desktopId == pinned
    }

    /** The `HelloAck`, or null when the connection ended or broke the handshake (then closed 1002). */
    private suspend fun awaitHelloAck(ws: DefaultClientWebSocketSession): HelloAck? {
        val frame = ws.incoming.receiveCatching().getOrNull() ?: return null
        val envelope = (frame as? Frame.Binary)?.let { runCatching { Envelope.ADAPTER.decode(it.readBytes()) }.getOrNull() }
        val ack =
            envelope
                ?.takeIf { it.channel == Protocol.CHANNEL_CORE && it.type == "HelloAck" }
                ?.let { runCatching { HelloAck.ADAPTER.decode(it.payload) }.getOrNull() }
        if (ack == null) ws.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Expected HelloAck"))
        return ack
    }

    private suspend fun closeOutcome(ws: DefaultClientWebSocketSession): Outcome {
        val reason = withTimeoutOrNull(config.closeTimeout) { ws.closeReason.await() }
        return if (reason?.code == CloseReason.Codes.VIOLATED_POLICY.code) Outcome.Rejected else Outcome.Lost
    }

    private suspend fun closeNormally(ws: DefaultClientWebSocketSession): Outcome {
        ws.close(CloseReason(CloseReason.Codes.NORMAL, ""))
        withTimeoutOrNull(config.closeTimeout) { for (frame in ws.incoming) Unit }
        return Outcome.Stopped
    }

    private fun helloEnvelope(): Envelope =
        Envelope(
            channel = Protocol.CHANNEL_CORE,
            type = "Hello",
            payload = hello.encodeByteString(),
            timestamp_ms = Clock.System.now().toEpochMilliseconds(),
        )

    private enum class Outcome { Lost, Rejected, Stopped }

    companion object {
        /** The close reason a device sends a desktop other than the one it pinned (ADR-0168). */
        const val PIN_REFUSAL: String = "Paired with another desktop"
    }
}
