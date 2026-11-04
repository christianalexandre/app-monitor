package appmonitor.sdk.core

import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointStage
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * One call's events and the decisions both Kotlin captures take on it (ADR-0182). Inline, so a [hold] or a wait
 * may block OkHttp's thread or suspend Ktor's coroutine; each library only reads and rebuilds its own call.
 */
@InternalAppMonitorApi
public class CallCapture(
    public val sink: CaptureSink,
    public val id: String = newRequestId(),
) {
    public fun started(message: () -> RequestStarted): Unit = sink.emit("RequestStarted", message)

    public fun received(message: () -> ResponseReceived): Unit = sink.emit("ResponseReceived", message)

    public fun failed(error: Throwable): Unit = sink.emit("RequestFailed") { requestFailed(id, error) }

    /** The rule's response once [wait] let its delay pass; its failure, or a failed [wait], fails the call (ADR-0110 C.1). */
    public inline fun answer(
        rule: MockRule,
        wait: (delayMs: Long) -> Unit,
    ): MockResponse {
        try {
            wait(rule.delay_ms)
            rule.failure?.let { throw IOException(it.message) }
        } catch (e: CancellationException) {
            failed(e)
            throw e
        } catch (e: IOException) {
            failed(e)
            throw e
        }
        return checkNotNull(rule.response)
    }

    public fun requestBreakpoint(
        method: String,
        url: String,
    ): BreakpointRule? = sink.breakpointFor(method, url, BreakpointStage.BREAKPOINT_STAGE_REQUEST)

    /** Null for a protocol upgrade too: its body is the connection, nothing a breakpoint could show or edit. */
    public fun responseBreakpoint(
        method: String,
        url: String,
        status: Int,
    ): BreakpointRule? =
        if (status == SWITCHING_PROTOCOLS) null else sink.breakpointFor(method, url, BreakpointStage.BREAKPOINT_STAGE_RESPONSE)

    /**
     * ADR-0111 A.1, C.1: what [edit] makes of the resume; an abort, a failed [hold] or an edit it refuses fails the
     * call, [started] captured first, since the request was not yet.
     */
    public inline fun <T> holdRequest(
        rule: BreakpointRule,
        started: RequestStarted,
        hold: (BreakpointHit) -> BreakpointOutcome,
        edit: (BreakpointOutcome.Resume) -> T,
    ): T {
        val hit =
            BreakpointHit(
                rule_id = rule.id,
                stage = BreakpointStage.BREAKPOINT_STAGE_REQUEST,
                request = started,
                timeout_ms = rule.timeout_ms,
            )
        val failure =
            try {
                when (val outcome = hold(hit)) {
                    is BreakpointOutcome.Abort -> IOException(outcome.message)
                    is BreakpointOutcome.Resume -> return edit(outcome)
                }
            } catch (e: CancellationException) {
                started { started }
                failed(e)
                throw e
            } catch (e: IOException) {
                e
            } catch (e: Exception) {
                IOException("$EDIT_REFUSED${e.message}", e)
            }
        started { started }
        failed(failure)
        throw failure
    }

    /**
     * ADR-0111 A.1, C.1: what [edit] makes of a resume changing the response, else null; an abort, a failed [hold] or an
     * edit it refuses [close]s the response and fails the call.
     */
    public inline fun <T : Any> holdResponse(
        rule: BreakpointRule,
        request: RequestStarted,
        response: ResponseReceived,
        hold: (BreakpointHit) -> BreakpointOutcome,
        close: () -> Unit,
        edit: (BreakpointOutcome.Resume) -> T,
    ): T? {
        val hit =
            BreakpointHit(
                rule_id = rule.id,
                stage = BreakpointStage.BREAKPOINT_STAGE_RESPONSE,
                request = request,
                response = response,
                timeout_ms = rule.timeout_ms,
            )
        val outcome =
            try {
                hold(hit)
            } catch (e: CancellationException) {
                close()
                failed(e)
                throw e
            } catch (e: IOException) {
                close()
                failed(e)
                throw e
            }
        when (outcome) {
            is BreakpointOutcome.Abort -> {
                close()
                val failure = IOException(outcome.message)
                failed(failure)
                throw failure
            }

            is BreakpointOutcome.Resume -> {
                if (!outcome.changesResponse) return null
                val failure =
                    try {
                        return edit(outcome)
                    } catch (e: CancellationException) {
                        close()
                        failed(e)
                        throw e
                    } catch (e: IOException) {
                        e
                    } catch (e: Exception) {
                        IOException("$EDIT_REFUSED${e.message}", e)
                    }
                close()
                failed(failure)
                throw failure
            }
        }
    }

    @InternalAppMonitorApi
    public companion object {
        public const val SWITCHING_PROTOCOLS: Int = 101

        /** What an edit a library refuses starts with, in the `RequestFailed` the desktop shows. */
        public const val EDIT_REFUSED: String = "Breakpoint edit refused: "

        /** Why a call whose body could be read only once is not sent again (ADR-0122). */
        public const val SENT_ONCE: String = "Its body could be sent only once"
    }
}
