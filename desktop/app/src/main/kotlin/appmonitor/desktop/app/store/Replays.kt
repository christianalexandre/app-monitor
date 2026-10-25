package appmonitor.desktop.app.store

import appmonitor.desktop.mcp.AskedReplay
import appmonitor.desktop.mcp.ReplayAnswer
import appmonitor.desktop.mcp.ReplayAsks
import appmonitor.desktop.server.DeviceSession
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.ReplayRefused
import appmonitor.v1.ReplayRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** What "Send again" did; the device runs the call later, or answers with a refusal (ADR-0111 D.1, ADR-0120). */
internal enum class ReplaySent {
    SENT,

    /** The exchange's session is over: a replay goes through the app, so only a live one can take it. */
    SESSION_ENDED,

    /** The device did not accept `interaction`. */
    NOT_TAKEN,
}

/** A request of [sessionId] (the server's session id) the desktop asked a device to send again. */
internal data class ReplayKey(
    val sessionId: String,
    val requestId: String,
)

/**
 * Sends `ReplayRequest` to a connected device and keeps, in memory until its session ends, the reason of each
 * `ReplayRefused` and each replay an agent [ask]ed that waits for the developer (ADR-0121 A.1).
 */
internal class Replays(
    private val clock: () -> Long = System::currentTimeMillis,
) : ReplayAsks {
    private val sessions = ConcurrentHashMap<String, DeviceSession>()

    private val mutableRefused = MutableStateFlow<Map<ReplayKey, String>>(emptyMap())

    /** The device's reason, by the request it did not send again; a new [send] of the same request clears it. */
    val refused: StateFlow<Map<ReplayKey, String>> = mutableRefused.asStateFlow()

    private val mutableAsked = MutableStateFlow<List<AskedReplay>>(emptyList())

    /** Replays an agent asked, oldest first, until the developer [answer]s them or their session ends. */
    val asked: StateFlow<List<AskedReplay>> = mutableAsked.asStateFlow()

    internal fun opened(session: DeviceSession) {
        sessions[session.id] = session
    }

    internal fun ended(sessionId: String) {
        sessions.remove(sessionId)
        mutableRefused.update { map -> map.filterKeys { it.sessionId != sessionId } }
        mutableAsked.update { list -> list.filter { it.sessionId != sessionId } }
    }

    override fun ask(replay: AskedReplay): ReplayAnswer {
        val session = sessions[replay.sessionId] ?: return ReplayAnswer.SESSION_ENDED
        if (Protocol.CHANNEL_INTERACTION !in session.channels) return ReplayAnswer.NOT_TAKEN
        var answer = ReplayAnswer.WAITING
        mutableAsked.update { list ->
            val waiting = list.any { it.exchangeId == replay.exchangeId }
            answer = if (!waiting && list.size >= ReplayAsks.MAX_WAITING) ReplayAnswer.TOO_MANY else ReplayAnswer.WAITING
            if (waiting || answer == ReplayAnswer.TOO_MANY) list else list + replay
        }
        // Its session ended between the check and the add: ended() may have filtered before it.
        if (!sessions.containsKey(replay.sessionId)) {
            mutableAsked.update { list -> list.filter { it.sessionId != replay.sessionId } }
            return ReplayAnswer.SESSION_ENDED
        }
        return answer
    }

    /** Sends [replay] when [send], else drops it; null when it no longer waits. */
    fun answer(
        replay: AskedReplay,
        send: Boolean,
    ): ReplaySent? {
        var waited = false
        mutableAsked.update { list ->
            waited = replay in list
            list - replay
        }
        return if (waited && send) send(replay.sessionId, replay.requestId) else null
    }

    /** Asks [sessionId]'s device to send [requestId] again. */
    fun send(
        sessionId: String,
        requestId: String,
    ): ReplaySent {
        val session = sessions[sessionId] ?: return ReplaySent.SESSION_ENDED
        if (Protocol.CHANNEL_INTERACTION !in session.channels) return ReplaySent.NOT_TAKEN
        mutableRefused.update { it - ReplayKey(sessionId, requestId) }
        val envelope =
            Envelope(
                channel = Protocol.CHANNEL_INTERACTION,
                type = "ReplayRequest",
                payload = ReplayRequest(requestId).encodeByteString(),
                timestamp_ms = clock(),
            )
        return if (session.send(envelope)) ReplaySent.SENT else ReplaySent.SESSION_ENDED
    }

    /** Keeps the reason of a well-formed `ReplayRefused`; true when [envelope] was one. */
    internal fun read(
        session: DeviceSession,
        envelope: Envelope,
    ): Boolean {
        if (envelope.channel != Protocol.CHANNEL_INTERACTION || envelope.type != "ReplayRefused") return false
        val refusal =
            try {
                ReplayRefused.ADAPTER.decode(envelope.payload)
            } catch (_: IOException) {
                return false
            }
        if (refusal.request_id.isEmpty() || !sessions.containsKey(session.id)) return false
        mutableRefused.update { it + (ReplayKey(session.id, refusal.request_id) to refusal.reason) }
        return true
    }
}
