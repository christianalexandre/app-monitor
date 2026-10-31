package appmonitor.sdk.core

import android.app.Application
import android.content.Context

/** Streams the app's traffic to the desktop; call [start] in `Application.onCreate` (ADR-0017), which asks for the local network permission (ADR-0156). */
public object AppMonitor {
    /** Starts the monitor; ignored while it runs or when [AppMonitorOptions.enabled] is false. */
    @JvmStatic
    @JvmOverloads
    public fun start(
        context: Context,
        options: AppMonitorOptions = AppMonitorOptions(),
    ) {
        val app = context.applicationContext
        if (options.enabled && app is Application) requestLocalNetworkOnFirstActivity(app)
        monitor.start(options) {
            MonitorClient(
                hello = { androidHello(app, it.appName) },
                discover = discovery(it, nsdBrowser(app), EMULATOR_HOST.takeIf { isEmulator() }),
            )
        }
    }

    /** Stops the monitor and drops what it had not sent; [start] runs a new one. */
    @JvmStatic
    public fun stop() {
        monitor.stop()
    }
}
