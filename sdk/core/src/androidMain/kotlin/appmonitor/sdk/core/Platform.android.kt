package appmonitor.sdk.core

import android.security.NetworkSecurityPolicy
import android.util.Log

internal actual fun isCleartextPermitted(host: String): Boolean = NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)

internal actual fun platformLog(message: String) {
    Log.w("AppMonitor", message)
}
