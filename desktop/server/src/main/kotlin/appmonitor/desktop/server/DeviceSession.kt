package appmonitor.desktop.server

import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * One handshaken device. [envelopes] emits everything sent after `Hello`, in order,
 * to a single collector, and completes when the connection closes.
 */
class DeviceSession internal constructor(
    val id: String,
    val hello: Hello,
    /** Channels accepted in the `HelloAck`. */
    val channels: List<String>,
) {
    private val buffer = Channel<Envelope>(Channel.BUFFERED)

    val envelopes: Flow<Envelope> = buffer.receiveAsFlow()

    // Suspends when the collector lags, which stops reading the socket (ADR-0009).
    internal suspend fun deliver(envelope: Envelope) = buffer.send(envelope)

    internal fun close() {
        buffer.close()
    }
}
