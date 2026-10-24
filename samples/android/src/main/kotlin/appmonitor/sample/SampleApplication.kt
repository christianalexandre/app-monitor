package appmonitor.sample

import android.app.Application
import appmonitor.sdk.core.AppMonitor
import appmonitor.sdk.core.AppMonitorOptions

class SampleApplication : Application() {
    private var launchExtrasApplied = false

    override fun onCreate() {
        super.onCreate()
        AppMonitor.start(this, options())
    }

    /** Restarts the monitor on the desktop at [host] only, as the `host` and `port` launch extras ask; once per process. */
    fun applyLaunchExtras(
        host: String,
        port: Int,
    ) {
        if (launchExtrasApplied) return
        launchExtrasApplied = true
        AppMonitor.stop()
        AppMonitor.start(this, options(host, port))
    }

    private fun options(
        host: String? = null,
        port: Int = AppMonitorOptions().port,
    ) = AppMonitorOptions(host = host, port = port, appName = "AppMonitor Sample")
}
