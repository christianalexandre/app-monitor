package appmonitor.sdk.core

import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointStage
import appmonitor.v1.Envelope
import appmonitor.v1.MockRule

/** Marks the API capture modules use to reach `sdk/core`; apps never call it (ADR-0025). */
@RequiresOptIn("Used by AppMonitor capture modules only.", RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class InternalAppMonitorApi

/** What a capture module hands to the running monitor. */
@InternalAppMonitorApi
public object MonitorBridge {
    /** The running monitor's options; null when it is not running and nothing should be captured. */
    public val options: AppMonitorOptions? get() = monitor.options

    /** Buffers [envelope] for the desktop; never blocks, dropped when the monitor is not running. */
    public fun send(envelope: Envelope) {
        monitor.client?.send(envelope)
    }

    /** The desktop's first rule matching the call (ADR-0110 B.1); null when none does or no desktop is connected. */
    public fun mockFor(
        method: String,
        url: String,
    ): MockRule? = monitor.client?.mocks?.match(method, url)

    /** The desktop's first breakpoint holding [stage] of the call (ADR-0118 A.1); null when none or no desktop is connected. */
    public fun breakpointFor(
        method: String,
        url: String,
        stage: BreakpointStage,
    ): BreakpointRule? = monitor.client?.breakpoints?.match(method, url, stage)

    /** Sends [hit] and suspends until the desktop resumes or aborts it, at most its timeout (ADR-0111 B.1). */
    public suspend fun hold(hit: BreakpointHit): BreakpointOutcome = monitor.client?.hold(hit) ?: BreakpointOutcome.UNCHANGED

    /**
     * Keeps [replay] to send [requestId]'s call again when the desktop asks (ADR-0111 D.1); it returns null once
     * the call is on its way, else the reason the desktop shows. Nothing is kept when no monitor runs.
     */
    public fun keepForReplay(
        requestId: String,
        replay: () -> String?,
    ) {
        monitor.client?.replays?.keep(requestId, replay)
    }
}
