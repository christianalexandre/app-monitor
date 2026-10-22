package appmonitor.sdk.core

import android.content.Context

/** Streams the app's traffic to the desktop; call [start] in `Application.onCreate` (ADR-0017). */
public object AppMonitor {
    private val holder = MonitorHolder()

    /** Starts the monitor; ignored while it runs or when [AppMonitorOptions.enabled] is false. */
    @JvmStatic
    @JvmOverloads
    public fun start(
        context: Context,
        options: AppMonitorOptions = AppMonitorOptions(),
    ) {
        val app = context.applicationContext
        holder.start(options) {
            MonitorClient(hello = { androidHello(app) }, discover = discovery(it, nsdBrowser(app), isEmulator()))
        }
    }

    /** Stops the monitor and drops what it had not sent; [start] runs a new one. */
    @JvmStatic
    public fun stop() {
        holder.stop()
    }
}
