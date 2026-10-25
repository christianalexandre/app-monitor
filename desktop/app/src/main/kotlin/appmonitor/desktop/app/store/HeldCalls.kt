package appmonitor.desktop.app.store

import appmonitor.desktop.server.DeviceSession
import appmonitor.protocol.Protocol
import appmonitor.v1.BreakpointAbort
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointResume
import appmonitor.v1.BreakpointStage
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.ByteString
import java.io.IOException

/** A call [sessionId]'s device holds at a breakpoint (ADR-0111 A.1); it goes on unchanged after [deadlineMs] (B.1). */
internal data class HeldCall(
    val sessionId: String,
    val appId: String,
    val hit: BreakpointHit,
    val heldAtMs: Long,
) {
    val requestId: String get() = hit.request?.request_id.orEmpty()

    val stage: BreakpointStage get() = hit.stage

    val deadlineMs: Long get() = heldAtMs + hit.timeout_ms

    /** The held side as the device sent it, to edit: the request's, or at the response stage the response's. */
    val unedited: HeldEdit
        get() {
            val request = checkNotNull(hit.request)
            val response = hit.response
            return if (stage == BreakpointStage.BREAKPOINT_STAGE_RESPONSE && response != null) {
                HeldEdit(request.method, request.url, response.status_code, response.headers, response.body)
            } else {
                HeldEdit(request.method, request.url, 0, request.headers, request.body)
            }
        }
}

/** The held side as the developer left it; [resumeOf] sends only what differs from [HeldCall.unedited]. */
internal data class HeldEdit(
    val method: String,
    val url: String,
    val statusCode: Int,
    val headers: List<Header>,
    val body: ByteString,
)

/** The calls every connected device holds, until each is resumed, aborted, timed out or its session ends (ADR-0118). */
internal class HeldCalls(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    private var calls = emptyList<Held>()

    private val mutableHeld = MutableStateFlow<List<HeldCall>>(emptyList())

    /** Oldest first. */
    val held: StateFlow<List<HeldCall>> = mutableHeld.asStateFlow()

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

    /** Lets [call] go on with [edit]'s changes only; false when it is no longer held or its device is gone. */
    fun resume(
        call: HeldCall,
        edit: HeldEdit = call.unedited,
    ): Boolean {
        require(edit.body.size <= Protocol.MAX_BODY_BYTES) { "A body is at most ${Protocol.MAX_BODY_BYTES} bytes" }
        val session = release(call) ?: return false
        return session.send(envelope("BreakpointResume", resumeOf(call, edit).encodeByteString()))
    }

    /** Fails [call] on the device with [message]; false when it is no longer held or its device is gone. */
    fun abort(
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
