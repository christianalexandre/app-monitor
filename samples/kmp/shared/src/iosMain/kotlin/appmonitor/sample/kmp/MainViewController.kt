package appmonitor.sample.kmp

import androidx.compose.ui.window.ComposeUIViewController
import appmonitor.sdk.core.AppMonitor
import appmonitor.sdk.core.AppMonitorOptions
import platform.UIKit.UIViewController

/** The Swift host's root view controller. */
fun mainViewController(
    runAll: Boolean,
    target: String?,
): UIViewController = ComposeUIViewController { App(runAll, target) }

/** Starts `sdk/core` on [host] when set, else by Bonjour (ADR-0062 B.1); in Release it is the no-op's. */
fun startMonitor(
    host: String?,
    port: Int,
) {
    AppMonitor.start(AppMonitorOptions(host = host, port = if (port > 0) port else AppMonitorOptions().port, appName = "KMP sample"))
}
