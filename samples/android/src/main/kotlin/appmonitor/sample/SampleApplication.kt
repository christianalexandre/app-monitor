package appmonitor.sample

import android.app.Application
import appmonitor.sdk.core.AppMonitor
import appmonitor.sdk.core.AppMonitorOptions

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppMonitor.start(this, options())
    }

    /** Restarts the monitor on the desktop at [host] only, as the `host` and `port` launch extras ask. */
    fun restartMonitor(
        host: String,
        port: Int,
    ) {
        AppMonitor.stop()
        AppMonitor.start(this, options(host, port))
    }

    private fun options(
        host: String? = null,
        port: Int = AppMonitorOptions().port,
    ) = AppMonitorOptions(host = host, port = port, appName = "AppMonitor Sample")
}
