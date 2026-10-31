package appmonitor.desktop.server

import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * One handshaken device. [envelopes] emits everything sent after `Hello`, in order,
 * to a single collector, and completes when the connection closes. [send] goes the other way.
 */
class DeviceSession internal constructor(
    val id: String,
    val hello: Hello,
    /** Channels accepted in the `HelloAck`. */
    val channels: List<String>,
    /** The device's address as the socket sees it. */
    val peerAddress: String,
) {
    private val buffer = Channel<Envelope>(Channel.BUFFERED)

    val envelopes: Flow<Envelope> = buffer.receiveAsFlow()

    private val outbox = Channel<Envelope>(Channel.UNLIMITED)

    private val mutableDrops = MutableStateFlow<Drops?>(null)

    /** What the device dropped since its SDK started, from its latest `DeliveryReport`; null before one (ADR-0166). */
    val drops: StateFlow<Drops?> = mutableDrops.asStateFlow()

    internal val outgoing: ReceiveChannel<Envelope> get() = outbox

    /**
     * Queues [envelope] for the device, in order and after `HelloAck`; false when its channel is not in [channels] or
     * the session is closed.
     */
    fun send(envelope: Envelope): Boolean = envelope.channel in channels && outbox.trySend(envelope).isSuccess

    // Suspends when the collector lags; the socket is still read meanwhile (ADR-0167).
    internal suspend fun deliver(envelope: Envelope) = buffer.send(envelope)

    internal fun report(drops: Drops) {
        mutableDrops.value = drops
    }

    internal fun close() {
        buffer.close()
        outbox.close()
    }
}

/**
 * Envelopes a device's SDK dropped since it started: full buffer, too large, or written and never read here,
 * null when a previous session of that start is not known here (ADR-0166).
 */
data class Drops(
    val evicted: Long,
    val oversized: Long,
    val lost: Long?,
)
