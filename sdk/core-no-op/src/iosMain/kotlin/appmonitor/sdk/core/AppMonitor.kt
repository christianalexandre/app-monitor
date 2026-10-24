package appmonitor.sdk.core

/** The release-build `AppMonitor` on iOS (ADR-0062 B.1): same calls as the real SDK, none of them does anything. */
public object AppMonitor {
    /** Does nothing. */
    public fun start(options: AppMonitorOptions = AppMonitorOptions()) {
    }

    /** Does nothing. */
    public fun stop() {
    }
}
