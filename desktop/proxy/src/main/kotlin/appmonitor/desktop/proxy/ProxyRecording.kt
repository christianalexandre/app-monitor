package appmonitor.desktop.proxy

import appmonitor.desktop.storage.Storage
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

/**
 * Writes what one [ProxyServer] relays to [storage] as one session, its device unknown (ADR-0101): [record] never
 * blocks the event loop, the events wait in memory for the store's queue. Not [open], it writes nothing until [open].
 */
class ProxyRecording(
    private val storage: Storage,
    scope: CoroutineScope,
    open: Boolean = true,
) {
    private val sessionId = "proxy-${UUID.randomUUID()}"
    private val events = Channel<Envelope>(Channel.UNLIMITED)
    private val opened = AtomicBoolean(open)
    private val writer =
        scope.launch(start = if (open) CoroutineStart.DEFAULT else CoroutineStart.LAZY) {
            storage.connected(sessionId, HELLO, ProxyServer.HOST)
            for (envelope in events) storage.received(sessionId, envelope)
        }

    fun record(envelope: Envelope) {
        events.trySend(envelope)
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
        storage.disconnected(sessionId)
    }

    companion object {
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
