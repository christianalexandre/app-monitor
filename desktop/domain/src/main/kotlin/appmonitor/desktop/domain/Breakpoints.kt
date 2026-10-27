package appmonitor.desktop.domain

import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointStage
import appmonitor.v1.Header
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import okio.ByteString

/** A stored breakpoint; [rule] carries [id], and a device of [appId] is sent it while [enabled] (ADR-0118). */
data class Breakpoint(
    val id: Long,
    val appId: String,
    val position: Long,
    val name: String,
    val enabled: Boolean,
    val rule: BreakpointRule,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** What [BreakpointRepository.update] changes; a null field keeps what is stored. [rule]'s id is ignored. */
data class BreakpointChange(
    val name: String? = null,
    val enabled: Boolean? = null,
    val rule: BreakpointRule? = null,
)

/** What a breakpoint write did, known once its batch is committed or lost. */
sealed interface BreakpointWrite {
    data class Saved(
        val id: Long,
    ) : BreakpointWrite

    /** Nothing written: the rule was deleted, or never stored. */
    data object UnknownBreakpoint : BreakpointWrite

    /** Its batch was lost, or the store was closing. */
    data object NotSaved : BreakpointWrite
}

/** Every app's breakpoints, in match order (ADR-0118). */
interface BreakpointRepository {
    /** Longest a breakpoint holds a call before it goes on unchanged. */
    val maxTimeoutMs: Long

    /** Every breakpoint, again after each change. */
    fun observeBreakpoints(): Flow<List<Breakpoint>>

    /** A breakpoint for [appId], last in its match order and on. Throws for an invalid rule. */
    suspend fun create(
        appId: String,
        name: String,
        rule: BreakpointRule,
    ): BreakpointWrite

    /** Throws for an invalid rule. */
    suspend fun update(
        id: Long,
        change: BreakpointChange,
    ): BreakpointWrite

    suspend fun delete(id: Long)
}

/** A call [sessionId]'s device holds at a breakpoint (ADR-0111 A.1); it goes on unchanged after [deadlineMs] (B.1). */
data class HeldCall(
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

/** The held side as the developer left it; a resume sends only what differs from [HeldCall.unedited] (ADR-0111 C.1). */
data class HeldEdit(
    val method: String,
    val url: String,
    val statusCode: Int,
    val headers: List<Header>,
    val body: ByteString,
)

/** The calls every connected device holds, until each is resumed, aborted, timed out or its session ends (ADR-0118). */
interface HeldCallRepository {
    /** Oldest first. */
    val held: StateFlow<List<HeldCall>>

    /** Lets [call] go on with [edit]'s changes only; false when it is no longer held or its device is gone. */
    fun resume(
        call: HeldCall,
        edit: HeldEdit = call.unedited,
    ): Boolean

    /** Fails [call] on the device with [message]; false when it is no longer held or its device is gone. */
    fun abort(
        call: HeldCall,
        message: String,
    ): Boolean
}
