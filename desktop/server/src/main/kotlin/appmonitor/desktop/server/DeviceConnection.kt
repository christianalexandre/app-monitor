package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameTooBigException
import io.ktor.websocket.ProtocolViolationException
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.time.Duration

/**
 * One device's WebSocket over a raw Ktor session (ADR-0167). Control frames are answered as they are read; data
 * frames reach [incoming] whole and in order, and the device's close only after them, so a rejection of one of
 * them is the close the device gets.
 */
internal class DeviceConnection(
    private val raw: WebSocketSession,
    /** The device's address as the socket sees it. */
    val peerAddress: String,
    private val pingPeriod: Duration,
    private val pingTimeout: Duration,
    private val closeGrace: Duration,
) : CoroutineScope by raw {
    // Unbounded: pongs are read however far the collector lags (ADR-0167).
    private val frames = Channel<Frame>(Channel.UNLIMITED)

    private val pongs = Channel<ByteArray>(Channel.UNLIMITED)

    private val closeSent = AtomicBoolean(false)

    @Volatile private var peerClose: CloseReason? = null

    private var reader: Job? = null

    private var pinger: Job? = null

    /** Whole data frames in the order read; completes after the device's close, a lost connection or [close]'s grace. */
    val incoming: ReceiveChannel<Frame> get() = frames

    fun start() {
        reader = launch { read() }
        pinger = launch { ping() }
    }

    suspend fun send(frame: Frame) = raw.send(frame)

    /** Sends [reason] once; [incoming] then completes at the device's close, or after the grace. */
    suspend fun close(reason: CloseReason) {
        if (!closeSent.compareAndSet(false, true)) return
        pinger?.cancel()
        writeClose(reason)
        launch {
            delay(closeGrace)
            frames.close()
        }
    }

    /** Answers a device's close not answered yet, then stops reading and pinging. */
    suspend fun finish() {
        val peer = peerClose
        if (peer != null && closeSent.compareAndSet(false, true)) writeClose(peer)
        pinger?.cancel()
        reader?.cancel()
    }

    private suspend fun read() {
        var first: Frame? = null
        val body = ByteArrayOutputStream()
        try {
            for (frame in raw.incoming) {
                when (frame) {
                    is Frame.Ping -> {
                        raw.outgoing.trySend(Frame.Pong(frame.data))
                    }

                    is Frame.Pong -> {
                        pongs.trySend(frame.data)
                    }

                    is Frame.Close -> {
                        peerClose = frame.readReason() ?: CloseReason(CloseReason.Codes.NORMAL, "")
                        return
                    }

                    else -> {
                        if (first == null && frame.fin) {
                            frames.trySend(frame)
                            continue
                        }
                        if (first == null) first = frame
                        if (body.size().toLong() + frame.data.size > Protocol.MAX_FRAME_BYTES) {
                            close(CloseReason(CloseReason.Codes.TOO_BIG, "Message over ${Protocol.MAX_FRAME_BYTES} bytes"))
                            return
                        }
                        body.write(frame.data)
                        if (frame.fin) {
                            frames.trySend(Frame.byType(true, first.frameType, body.toByteArray(), false, false, false))
                            first = null
                            body.reset()
                        }
                    }
                }
            }
        } catch (_: FrameTooBigException) {
            sentByKtor()
        } catch (_: ProtocolViolationException) {
            sentByKtor()
        } catch (_: IOException) {
            Unit
        } catch (_: ClosedReceiveChannelException) {
            Unit
        } finally {
            frames.close()
        }
    }

    // Ktor only queued its 1009 or 1002 close: the handler returning before it is written loses it.
    private suspend fun sentByKtor() {
        closeSent.set(true)
        withTimeoutOrNull(closeGrace) { raw.flush() }
    }

    private suspend fun ping() {
        while (true) {
            delay(pingPeriod)
            val payload = Random.nextBytes(PING_BYTES)
            while (pongs.tryReceive().isSuccess) Unit
            if (closeSent.get() || raw.outgoing.trySend(Frame.Ping(payload)).isFailure) return
            val answered = withTimeoutOrNull(pingTimeout) { while (!pongs.receive().contentEquals(payload)) Unit }
            if (answered == null) {
                if (closeSent.compareAndSet(false, true)) {
                    raw.outgoing.trySend(Frame.Close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Ping timeout")))
                }
                frames.close()
                reader?.cancel()
                return
            }
        }
    }

    private suspend fun writeClose(reason: CloseReason) {
        try {
            raw.outgoing.send(Frame.Close(reason))
            withTimeoutOrNull(closeGrace) { raw.flush() }
        } catch (_: Exception) {
            Unit
        }
    }

    private companion object {
        const val PING_BYTES = 16
    }
}
