package appmonitor.desktop.proxy

import appmonitor.desktop.storage.TrafficSink
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes what one [ProxyServer] relays to [sink] as one session, its device unknown (ADR-0101): [record] never
 * blocks the event loop, the events wait in memory for the sink, up to [maxPendingBytes] of payloads, and past
 * that are dropped. Not [open], it writes nothing until [open].
 */
class ProxyRecording(
    private val sink: TrafficSink,
    scope: CoroutineScope,
    open: Boolean = true,
    private val maxPendingBytes: Long = MAX_PENDING_BYTES,
) {
    private val sessionId = "proxy-${UUID.randomUUID()}"
    private val events = Channel<Envelope>(Channel.UNLIMITED)
    private val pending = AtomicLong()
    private val droppedCount = AtomicLong()
    private val opened = AtomicBoolean(open)
    private val writer =
        scope.launch(start = if (open) CoroutineStart.DEFAULT else CoroutineStart.LAZY) {
            sink.connected(sessionId, HELLO, ProxyServer.HOST)
            for (envelope in events) {
                sink.received(sessionId, envelope)
                pending.addAndGet(-envelope.payload.size.toLong())
            }
        }

    /** Events [record] dropped because [maxPendingBytes] were already waiting. */
    val dropped: Long get() = droppedCount.get()

    fun record(envelope: Envelope) {
        val size = envelope.payload.size.toLong()
        if (pending.addAndGet(size) > maxPendingBytes || events.trySend(envelope).isFailure) {
            pending.addAndGet(-size)
            droppedCount.incrementAndGet()
        }
    }

    /** Writes the session, then what [record] kept so far and everything after. */
    fun open() {
        opened.set(true)
        writer.start()
    }

    /** Writes what is left, then ends the session; call after the server stopped. Never opened: writes nothing. */
    suspend fun close() {
        events.close()
        if (!opened.get()) {
            writer.cancel()
            return
        }
        writer.join()
        sink.disconnected(sessionId)
    }

    companion object {
        /** As the store's write queue (SDD 09): about 50 envelopes of `Protocol.MAX_FRAME_BYTES`. */
        const val MAX_PENDING_BYTES = 64L * 1024 * 1024

        /** What a proxy session shows for its device: none is known (ADR-0101 A.1). */
        val HELLO =
            Hello(
                protocol_version = Protocol.VERSION,
                platform = Platform.PLATFORM_UNSPECIFIED,
                device_name = "Proxy",
                channels = listOf(Protocol.CHANNEL_NETWORK),
                sdk_name = "proxy",
            )
    }
}
