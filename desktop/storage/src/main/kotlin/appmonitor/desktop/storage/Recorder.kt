package appmonitor.desktop.storage

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Hello
import com.squareup.wire.ProtoReader
import okio.Buffer
import okio.ByteString
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Feeds [live] every session, and [storage] each exchange that starts while [Storage.recording] is set, in that run until
 * its last event, and other channels while recording (ADR-0142). A session gets a row in a run with its first envelope there.
 */
class Recorder(
    private val live: LiveTraffic,
    private val storage: Storage,
    private val clock: () -> Long = System::currentTimeMillis,
) : TrafficSink {
    // Each session's calls come in order from one caller; only [forget] runs beside them.
    private class Device(
        val hello: Hello,
        val peerAddress: String?,
        val atMs: Long,
    ) {
        val runs: MutableSet<Long> = ConcurrentHashMap.newKeySet()

        @Volatile var drops: StoredDrops? = null
        val exchanges = ConcurrentHashMap<String, Long>()
    }

    private val devices = ConcurrentHashMap<String, Device>()

    override suspend fun connected(
        sessionId: String,
        hello: Hello,
        peerAddress: String?,
    ) {
        devices[sessionId] = Device(hello, peerAddress, clock())
        live.connected(sessionId, hello, peerAddress)
    }

    override suspend fun received(
        sessionId: String,
        envelope: Envelope,
    ) {
        live.received(sessionId, envelope)
        val device = devices[sessionId] ?: return
        val run = runOf(device, envelope) ?: return
        val key = keyOf(sessionId, run)
        if (device.runs.add(run)) {
            storage.connected(key, sessionId, device.hello, device.peerAddress, run, device.atMs)
            device.drops?.let { storage.dropped(key, it) }
        }
        storage.received(key, envelope)
    }

    override suspend fun dropped(
        sessionId: String,
        drops: StoredDrops,
    ) {
        live.dropped(sessionId, drops)
        val device = devices[sessionId] ?: return
        device.drops = drops
        device.runs.forEach { storage.dropped(keyOf(sessionId, it), drops) }
    }

    override suspend fun disconnected(sessionId: String) {
        live.disconnected(sessionId)
        val device = devices.remove(sessionId) ?: return
        device.runs.forEach { storage.disconnected(keyOf(sessionId, it)) }
    }

    /** After [Storage.clearAll]: the store forgot every session, so nothing more of what was recorded is stored. */
    fun forget() {
        devices.values.forEach {
            it.runs.clear()
            it.exchanges.clear()
        }
    }

    private fun runOf(
        device: Device,
        envelope: Envelope,
    ): Long? {
        val recording = storage.recording.value
        if (envelope.channel != Protocol.CHANNEL_NETWORK || envelope.type !in EXCHANGE_EVENTS) return recording
        val requestId = requestIdOf(envelope.payload) ?: return null
        if (envelope.type == STARTED && recording != null) device.exchanges[requestId] = recording
        return device.exchanges[requestId]
    }

    private fun keyOf(
        sessionId: String,
        run: Long,
    ) = "$sessionId@$run"

    private companion object {
        const val STARTED = "RequestStarted"
        val EXCHANGE_EVENTS = setOf(STARTED, "ResponseReceived", "RequestFailed", "WebSocketFrame")

        // Field 1 of every exchange event; the rest, bodies included, is skipped, not copied.
        fun requestIdOf(payload: ByteString): String? =
            try {
                val reader = ProtoReader(Buffer().write(payload))
                val token = reader.beginMessage()
                var id: String? = null
                while (true) {
                    val tag = reader.nextTag()
                    if (tag == -1) break
                    if (tag == 1) id = reader.readString() else reader.skip()
                }
                reader.endMessageAndGetUnknownFields(token)
                id
            } catch (_: IOException) {
                null
            }
    }
}
