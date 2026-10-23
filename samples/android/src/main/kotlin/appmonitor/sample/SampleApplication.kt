package appmonitor.sample

import android.app.Application
import appmonitor.sdk.core.AppMonitor
import appmonitor.sdk.core.AppMonitorOptions

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppMonitor.start(this, AppMonitorOptions(appName = "AppMonitor Sample"))
    }
}
