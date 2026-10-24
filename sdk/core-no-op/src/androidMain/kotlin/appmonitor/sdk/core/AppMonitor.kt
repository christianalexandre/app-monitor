package appmonitor.sdk.core

import android.content.Context

/** The release-build `AppMonitor` (ADR-0017): same calls as the real SDK, none of them does anything. */
public object AppMonitor {
    /** Does nothing. */
    @JvmStatic
    @JvmOverloads
    public fun start(
        context: Context,
        options: AppMonitorOptions = AppMonitorOptions(),
    ) {
    }

    /** Does nothing. */
    @JvmStatic
    public fun stop() {
    }
}
