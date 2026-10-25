package appmonitor.desktop.storage

import appmonitor.desktop.storage.db.Breakpoint_rules
import appmonitor.v1.BreakpointRule

/** A stored breakpoint; [rule] carries [id], and a device of [appId] is sent it while [enabled] (ADR-0118). */
data class StoredBreakpoint(
    val id: Long,
    val appId: String,
    val position: Long,
    val name: String,
    val enabled: Boolean,
    val rule: BreakpointRule,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** What [Storage.updateBreakpoint] changes; a null field keeps what is stored. [rule]'s id is ignored. */
data class BreakpointChange(
    val name: String? = null,
    val enabled: Boolean? = null,
    val rule: BreakpointRule? = null,
)

/** What a queued breakpoint write did, known once its batch is committed or lost. */
sealed interface BreakpointWrite {
    data class Saved(
        val id: Long,
    ) : BreakpointWrite

    /** Nothing written: the rule was deleted, or never stored. */
    data object UnknownBreakpoint : BreakpointWrite

    /** Its batch was lost, or the store was closing. */
    data object NotSaved : BreakpointWrite
}

/** Longest a breakpoint holds a call before it goes on unchanged (ADR-0118). */
const val MAX_BREAKPOINT_TIMEOUT_MS: Long = 10 * 60_000L

/** Throws for a rule no SDK can match or hold with (ADR-0111 A.1, B.1). */
internal fun requireValid(rule: BreakpointRule) {
    require(rule.host.isNotBlank()) { "A breakpoint needs a host" }
    require(rule.path.startsWith("/")) { "A breakpoint's path starts with /" }
    require(rule.request || rule.response) { "A breakpoint holds the request, the response or both" }
    require(rule.timeout_ms in 1..MAX_BREAKPOINT_TIMEOUT_MS) { "A breakpoint's timeout is 1 ms to $MAX_BREAKPOINT_TIMEOUT_MS ms" }
}

// The stored blob holds no id: the row's is the one sent.
internal fun BreakpointRule.stored(): ByteArray = copy(id = 0).encode()

internal fun Breakpoint_rules.toStored() =
    StoredBreakpoint(
        id = id,
        appId = app_id,
        position = position,
        name = name,
        enabled = enabled != 0L,
        rule = BreakpointRule.ADAPTER.decode(rule).copy(id = id),
        createdAtMs = created_at_ms,
        updatedAtMs = updated_at_ms,
    )
