package appmonitor.desktop.app.store

import appmonitor.desktop.domain.HeldCall
import appmonitor.desktop.domain.HeldCallRepository
import appmonitor.desktop.domain.HeldEdit
import appmonitor.desktop.server.DeviceSession
import appmonitor.protocol.Protocol
import appmonitor.v1.BreakpointAbort
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointResume
import appmonitor.v1.BreakpointStage
import appmonitor.v1.Envelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.ByteString
import java.io.IOException

/** The calls every connected device holds, until each is resumed, aborted, timed out or its session ends (ADR-0118). */
internal class HeldCalls(
    private val clock: () -> Long = System::currentTimeMillis,
) : HeldCallRepository {
    private val lock = Any()

    private var calls = emptyList<Held>()

    private val mutableHeld = MutableStateFlow<List<HeldCall>>(emptyList())

    override val held: StateFlow<List<HeldCall>> = mutableHeld.asStateFlow()

    /** The call [envelope] holds, replacing one held under the same request and stage; null for anything else. */
    internal fun hold(
        session: DeviceSession,
        envelope: Envelope,
    ): HeldCall? {
        if (envelope.channel != Protocol.CHANNEL_INTERACTION || envelope.type != "BreakpointHit") return null
        val hit =
            try {
                BreakpointHit.ADAPTER.decode(envelope.payload)
            } catch (_: IOException) {
                return null
            }
        val call = HeldCall(session.id, session.hello.app_id, hit, clock())
        if (!call.isWellFormed()) return null
        change { list -> list.filterNot { it.call.sameHold(call) } + Held(call, session) }
        return call
    }

    override fun resume(
        call: HeldCall,
        edit: HeldEdit,
    ): Boolean {
        require(edit.body.size <= Protocol.MAX_BODY_BYTES) { "A body is at most ${Protocol.MAX_BODY_BYTES} bytes" }
        val session = release(call) ?: return false
        return session.send(envelope("BreakpointResume", resumeOf(call, edit).encodeByteString()))
    }

    override fun abort(
        call: HeldCall,
        message: String,
    ): Boolean {
        val session = release(call) ?: return false
        return session.send(envelope("BreakpointAbort", BreakpointAbort(call.requestId, call.stage, message).encodeByteString()))
    }

    /** Drops [call] once its timeout passed: the device let it go on (ADR-0111 B.1). */
    internal fun expire(call: HeldCall) {
        release(call)
    }

    /** Drops every call of [sessionId]: its device let them go on (ADR-0111 B.1). */
    internal fun ended(sessionId: String) {
        change { list -> list.filterNot { it.call.sessionId == sessionId } }
    }

    // The very instance: a newer hit under the same request and stage is not released by an older call's timeout.
    private fun release(call: HeldCall): DeviceSession? {
        var session: DeviceSession? = null
        change { list ->
            val held = list.firstOrNull { it.call === call }
            session = held?.session
            list - listOfNotNull(held)
        }
        return session
    }

    private fun change(transform: (List<Held>) -> List<Held>) {
        synchronized(lock) {
            calls = transform(calls)
            mutableHeld.value = calls.map { it.call }
        }
    }

    private fun envelope(
        type: String,
        payload: ByteString,
    ) = Envelope(channel = Protocol.CHANNEL_INTERACTION, type = type, payload = payload, timestamp_ms = clock())

    private class Held(
        val call: HeldCall,
        val session: DeviceSession,
    )
}

/** Only what [edit] changes in [call]'s held side, so a masked value left alone keeps the device's own (ADR-0111 C.1). */
internal fun resumeOf(
    call: HeldCall,
    edit: HeldEdit,
): BreakpointResume {
    val before = call.unedited
    val request = call.stage == BreakpointStage.BREAKPOINT_STAGE_REQUEST
    val beforeGroups = before.headers.groupBy { it.name.lowercase() }
    val editGroups = edit.headers.groupBy { it.name.lowercase() }
    return BreakpointResume(
        request_id = call.requestId,
        stage = call.stage,
        method = edit.method.takeIf { request && it != before.method },
        url = edit.url.takeIf { request && it != before.url },
        status_code = edit.statusCode.takeIf { !request && it != before.statusCode },
        set_headers = editGroups.filter { (name, headers) -> beforeGroups[name] != headers }.values.flatten(),
        removed_headers = beforeGroups.filterKeys { it !in editGroups }.values.map { it.first().name },
        body = edit.body.takeIf { it != before.body },
    )
}

private fun HeldCall.isWellFormed(): Boolean =
    requestId.isNotEmpty() &&
        hit.timeout_ms > 0 &&
        when (stage) {
            BreakpointStage.BREAKPOINT_STAGE_REQUEST -> true
            BreakpointStage.BREAKPOINT_STAGE_RESPONSE -> hit.response != null
            else -> false
        }

private fun HeldCall.sameHold(other: HeldCall) = sessionId == other.sessionId && requestId == other.requestId && stage == other.stage
