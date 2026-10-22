package appmonitor.sdk.core

import appmonitor.v1.Envelope

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
}
