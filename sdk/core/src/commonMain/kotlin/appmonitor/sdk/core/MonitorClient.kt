package appmonitor.sdk.core

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.HelloAck
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where a desktop listens. */
internal data class Endpoint(
    val host: String,
    val port: Int = Protocol.DEFAULT_PORT,
    val path: String = Protocol.WEBSOCKET_PATH,
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
    private val hello: Hello,
    private val discover: suspend () -> Endpoint?,
    private val config: ClientConfig = ClientConfig(),
    private val cleartextPermitted: (host: String) -> Boolean = ::isCleartextPermitted,
    private val log: (String) -> Unit = ::platformLog,
    random: Random = Random.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val buffer = EventBuffer(config.bufferCount, config.bufferBytes)
    private val backoff = Backoff(config.backoffInitial, config.backoffMax, random = random)
    private val stopRequested = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(ClientState.Idle)

    private val lock = Any()
    private var client: HttpClient? = null
    private var running: Job? = null
    private var releasing: Job? = null

    // Under lock with stopRequested: stop leaves an open session to close itself with 1000.
    private var inSession = false

    val state: StateFlow<ClientState> = mutableState.asStateFlow()

    /** Envelopes dropped by the buffer or for their size (DEBT-0023). */
    val dropped: Long get() = buffer.dropped + oversized.get()

    private val oversized = AtomicLong()

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

    /** Buffers [envelope] for the desktop; dropped when stopped or larger than a frame (ADR-0022). */
    fun send(envelope: Envelope) {
        if (stopRequested.isCompleted) return
        val bytes = envelope.encode()
        if (bytes.size > Protocol.MAX_FRAME_BYTES) {
            oversized.incrementAndGet()
            return
        }
        buffer.offer(BufferedFrame(envelope.channel, bytes))
    }

    /** Returns at once; the release runs on the client's scope, bounded by [ClientConfig.closeTimeout]. */
    fun stop() {
        val run: Job?
        synchronized(lock) {
            if (!stopRequested.complete(Unit)) return
            run = running
            if (!inSession) run?.cancel()
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
                if (!cleartextPermitted(endpoint.host)) {
                    log(
                        "cleartext ws:// to ${endpoint.host} is blocked by the app's network security policy; " +
                            "allow it for debug builds with <debug-overrides> (see the SDK README)",
                    )
                    stop()
                    return
                }
                when (connect(endpoint)) {
                    Outcome.Stopped -> return
                    Outcome.Rejected -> backoff.exhaust()
                    Outcome.Lost -> Unit
                }
            }
            mutableState.value = ClientState.BackingOff
            delay(backoff.nextDelay())
        }
    }

    private suspend fun connect(endpoint: Endpoint): Outcome {
        mutableState.value = ClientState.Connecting
        val http = checkNotNull(client)
        return try {
            coroutineScope {
                val upgraded = CompletableDeferred<Unit>()
                val watchdog =
                    launch {
                        withTimeoutOrNull(config.connectTimeout) { upgraded.await() }
                            ?: throw IOException("No WebSocket upgrade within ${config.connectTimeout}")
                    }
                var outcome = Outcome.Lost
                http.webSocket(host = endpoint.host, port = endpoint.port, path = endpoint.path) {
                    upgraded.complete(Unit)
                    outcome = session(this)
                }
                watchdog.cancel()
                outcome
            }
        } catch (e: CancellationException) {
            // Ktor cancels the session's channels when the connection drops.
            currentCoroutineContext().ensureActive()
            Outcome.Lost
        } catch (e: Exception) {
            Outcome.Lost
        }
    }

    private suspend fun session(ws: DefaultClientWebSocketSession): Outcome {
        val stopFirst =
            synchronized(lock) {
                inSession = true
                stopRequested.isCompleted
            }
        try {
            if (stopFirst) return closeNormally(ws)
            mutableState.value = ClientState.Handshaking
            return coroutineScope {
                val stop = async { stopRequested.await() }
                val serve = async { serveUntilClosed(ws) }
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
        } finally {
            synchronized(lock) { inSession = false }
        }
    }

    /** [serve], and the desktop's close when a write fails: a 1008 can arrive before `Hello` is written. */
    private suspend fun serveUntilClosed(ws: DefaultClientWebSocketSession): Outcome =
        try {
            serve(ws)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            closeOutcome(ws)
        } catch (e: Exception) {
            closeOutcome(ws)
        }

    private suspend fun serve(ws: DefaultClientWebSocketSession): Outcome {
        ws.send(Frame.Binary(true, helloEnvelope().encode()))
        val ack = awaitHelloAck(ws) ?: return closeOutcome(ws)
        backoff.reset()
        mutableState.value = ClientState.Connected
        val accepted = ack.channels.toSet()
        coroutineScope {
            val sender =
                launch {
                    while (true) {
                        val frame = buffer.take()
                        if (frame.channel in accepted) ws.send(Frame.Binary(true, frame.bytes))
                    }
                }
            // The desktop sends nothing after HelloAck; this ends with the connection.
            for (frame in ws.incoming) Unit
            sender.cancel()
        }
        return closeOutcome(ws)
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
            timestamp_ms = System.currentTimeMillis(),
        )

    private enum class Outcome { Lost, Rejected, Stopped }
}
