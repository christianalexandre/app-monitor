package appmonitor.sdk.core

/** Streams the app's traffic to the desktop; call [start] at the app's entry point (ADR-0062 B.1). */
public object AppMonitor {
    /** Starts the monitor; ignored while it runs or when [AppMonitorOptions.enabled] is false. Never blocks, never throws. */
    public fun start(options: AppMonitorOptions = AppMonitorOptions()) {
        monitor.start(options) {
            MonitorClient(
                hello = { iosHello(appName = it.appName) },
                discover = discovery(it, bonjourBrowser(), SIMULATOR_HOST.takeIf { isSimulator }),
            )
        }
    }

    /** Stops the monitor and drops what it had not sent; [start] runs a new one. */
    public fun stop() {
        monitor.stop()
    }
}
