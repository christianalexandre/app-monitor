package appmonitor.sdk.core

import appmonitor.v1.BreakpointAbort
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointResume
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointStage
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/** How a held call goes on (ADR-0111 A.1, B.1, C.1). */
@InternalAppMonitorApi
public sealed class BreakpointOutcome {
    /** Goes on with [changes]' fields only; null when unchanged: no resume, its timeout, or its session's end. */
    @InternalAppMonitorApi
    public class Resume(
        public val changes: BreakpointResume?,
    ) : BreakpointOutcome() {
        /** [headers] with the resume's `set_headers` and `removed_headers` applied, names ignoring case (ADR-0118 C.1). */
        public fun headers(headers: List<Pair<String, String>>): List<Pair<String, String>> {
            val resume = changes ?: return headers
            val set = resume.set_headers.mapTo(HashSet()) { it.name.lowercase() }
            val removed = resume.removed_headers.mapTo(HashSet()) { it.lowercase() }
            val kept = headers.filter { (name, _) -> name.lowercase().let { it !in set && it !in removed } }
            return kept + resume.set_headers.map { it.name to it.value_ }
        }

        /** True when [headers] would change. */
        public val changesHeaders: Boolean get() =
            changes != null &&
                (changes.set_headers.isNotEmpty() || changes.removed_headers.isNotEmpty())
    }

    /** Fails the call as a mock failure does, with [message]. */
    @InternalAppMonitorApi
    public class Abort(
        public val message: String,
    ) : BreakpointOutcome()

    @InternalAppMonitorApi
    public companion object {
        public val UNCHANGED: BreakpointOutcome = Resume(null)
    }
}

/** The session's last `BreakpointRules` and the calls they hold (ADR-0118); empty outside a session. */
@OptIn(InternalAppMonitorApi::class)
internal class Breakpoints {
    @Volatile
    private var rules: List<BreakpointRule> = emptyList()

    private val lock = SynchronizedObject()
    private val held = HashMap<Pair<String, BreakpointStage>, CompletableDeferred<BreakpointOutcome>>()
    private var live = false

    fun replace(rules: List<BreakpointRule>) {
        synchronized(lock) { live = true }
        this.rules = rules.filter { (it.request || it.response) && it.timeout_ms > 0 }
    }

    /** The session ended: every held call goes on unchanged (ADR-0111 B.1). */
    fun clear() {
        rules = emptyList()
        val waiting =
            synchronized(lock) {
                live = false
                held.values.toList().also { held.clear() }
            }
        waiting.forEach { it.complete(BreakpointOutcome.UNCHANGED) }
    }

    /** The first rule matching [method] and [url] that holds [stage], or null. */
    fun match(
        method: String,
        url: String,
        stage: BreakpointStage,
    ): BreakpointRule? {
        val current = rules
        if (current.isEmpty()) return null
        val (host, path) = hostAndPath(url) ?: return null
        return current.firstOrNull {
            (if (stage == BreakpointStage.BREAKPOINT_STAGE_REQUEST) it.request else it.response) &&
                ruleMatches(it.method, it.host, it.path, method, host, path)
        }
    }

    /** Sends [hit] by [send] and waits for its resume or abort, at most its timeout; unchanged outside a session. */
    suspend fun hold(
        hit: BreakpointHit,
        send: (BreakpointHit) -> Unit,
    ): BreakpointOutcome {
        val id = hit.request?.request_id ?: return BreakpointOutcome.UNCHANGED
        val key = id to hit.stage
        val outcome = CompletableDeferred<BreakpointOutcome>()
        synchronized(lock) {
            if (!live) return BreakpointOutcome.UNCHANGED
            held.put(key, outcome)?.complete(BreakpointOutcome.UNCHANGED)
        }
        try {
            send(hit)
            return withTimeoutOrNull(hit.timeout_ms) { outcome.await() } ?: BreakpointOutcome.UNCHANGED
        } finally {
            synchronized(lock) { if (held[key] === outcome) held.remove(key) }
        }
    }

    /** A resume for a call no longer held, past its timeout, is dropped (ADR-0118). */
    fun resume(resume: BreakpointResume) {
        take(resume.request_id, resume.stage)?.complete(BreakpointOutcome.Resume(resume))
    }

    fun abort(abort: BreakpointAbort) {
        take(abort.request_id, abort.stage)?.complete(BreakpointOutcome.Abort(abort.message))
    }

    private fun take(
        requestId: String,
        stage: BreakpointStage,
    ) = synchronized(lock) { held.remove(requestId to stage) }
}
