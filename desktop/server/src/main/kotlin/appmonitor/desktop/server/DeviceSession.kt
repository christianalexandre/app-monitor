package appmonitor.desktop.server

import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
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

    internal val outgoing: ReceiveChannel<Envelope> get() = outbox

    /**
     * Queues [envelope] for the device, in order and after `HelloAck`; false when its channel is not in [channels] or
     * the session is closed.
     */
    fun send(envelope: Envelope): Boolean = envelope.channel in channels && outbox.trySend(envelope).isSuccess

    // Suspends when the collector lags, which stops reading the socket (ADR-0009).
    internal suspend fun deliver(envelope: Envelope) = buffer.send(envelope)

    internal fun close() {
        buffer.close()
        outbox.close()
    }
}
