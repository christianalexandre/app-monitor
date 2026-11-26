package appmonitor.desktop.app.store

import appmonitor.desktop.domain.MonitorRepository
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * ADR-0222: while paused, a request that starts is left out with every later event of its own, so the list never holds half
 * of one; a request that started before the pause ends as usual. Every other envelope passes.
 */
internal class MonitorPause : MonitorRepository {
    private val state = MutableStateFlow(false)
    private val lock = Any()
    private val skipped = HashMap<String, MutableSet<String>>()

    override val paused: StateFlow<Boolean> = state.asStateFlow()

    override fun pause() {
        state.value = true
    }

    override fun resume() {
        state.value = false
    }

    /** Whether [envelope] of session [sessionId] goes to the list and the store. */
    fun admits(
        sessionId: String,
        envelope: Envelope,
    ): Boolean {
        if (envelope.channel != Protocol.CHANNEL_NETWORK) return true
        val requestId = requestIdOf(envelope) ?: return true
        synchronized(lock) {
            val ids = skipped[sessionId]
            if (envelope.type == STARTED && state.value) {
                skipped.getOrPut(sessionId) { HashSet() } += requestId
                return false
            }
            return ids == null || requestId !in ids
        }
    }

    /** Forgets session [sessionId]'s skipped requests once it ends. */
    fun ended(sessionId: String) {
        synchronized(lock) { skipped.remove(sessionId) }
    }

    private fun requestIdOf(envelope: Envelope): String? =
        try {
            when (envelope.type) {
                STARTED -> RequestStarted.ADAPTER.decode(envelope.payload).request_id
                "ResponseReceived" -> ResponseReceived.ADAPTER.decode(envelope.payload).request_id
                "RequestFailed" -> RequestFailed.ADAPTER.decode(envelope.payload).request_id
                "WebSocketFrame" -> WebSocketFrame.ADAPTER.decode(envelope.payload).request_id
                else -> null
            }
        } catch (_: IOException) {
            null
        }

    private companion object {
        const val STARTED = "RequestStarted"
    }
}
