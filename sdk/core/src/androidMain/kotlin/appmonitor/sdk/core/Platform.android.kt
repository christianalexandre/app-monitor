package appmonitor.sdk.core

import android.util.Log
import kotlinx.io.files.Path

internal actual fun platformLog(message: String) {
    Log.w("AppMonitor", message)
}

internal actual val CONNECT_FAILURE_HINT: String = "On Android 17+ check ACCESS_LOCAL_NETWORK"

/** SELinux denies `link` to apps (ADR-0080). */
internal actual fun linkExclusive(
    existing: Path,
    target: Path,
): Boolean? = null
