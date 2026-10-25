package appmonitor.desktop.proxy

import appmonitor.desktop.storage.Storage
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Writes what one [ProxyServer] relays to [storage] as one session, its device unknown (ADR-0101): [record] never
 * blocks the event loop, the events wait in memory for the store's queue.
 */
class ProxyRecording(
    private val storage: Storage,
    scope: CoroutineScope,
) {
    private val sessionId = "proxy-${UUID.randomUUID()}"
    private val events = Channel<Envelope>(Channel.UNLIMITED)
    private val writer =
        scope.launch {
            storage.connected(sessionId, HELLO, ProxyServer.HOST)
            for (envelope in events) storage.received(sessionId, envelope)
        }

    fun record(envelope: Envelope) {
        events.trySend(envelope)
    }

    /** Writes what is left, then ends the session; call after the server stopped. */
    suspend fun close() {
        events.close()
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
