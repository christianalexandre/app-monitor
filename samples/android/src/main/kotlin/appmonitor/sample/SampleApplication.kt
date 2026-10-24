package appmonitor.sample

import android.app.Application
import appmonitor.sdk.core.AppMonitor
import appmonitor.sdk.core.AppMonitorOptions

class SampleApplication : Application() {
    private var appliedTarget: Pair<String, Int>? = null

    override fun onCreate() {
        super.onCreate()
        AppMonitor.start(this, options())
    }

    /** Restarts the monitor on the desktop at [host] only, as the `host` and `port` launch extras ask; again only when they change. */
    fun applyLaunchExtras(
        host: String,
        port: Int,
    ) {
        val target = host to port
        if (appliedTarget == target) return
        AppMonitor.stop()
        AppMonitor.start(this, options(host, port))
        appliedTarget = target
    }

    private fun options(
        host: String? = null,
        port: Int = AppMonitorOptions().port,
    ) = AppMonitorOptions(host = host, port = port, appName = "AppMonitor Sample")
}
