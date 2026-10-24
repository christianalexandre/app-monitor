package appmonitor.sdk.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.Foundation.NSLog
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.link

/** iOS has no per-host cleartext policy CIO's sockets would obey; ATS governs `URLSession` only (ADR-0066 A). */
internal actual fun isCleartextPermitted(host: String): Boolean = true

internal actual fun platformLog(message: String) {
    NSLog("AppMonitor: %@", message)
}

// ADR-0063 B.1
internal actual val CONNECT_FAILURE_HINT: String =
    "On a device check NSLocalNetworkUsageDescription and NSBonjourServices in Info.plist"

@OptIn(ExperimentalForeignApi::class)
internal actual fun linkExclusive(
    existing: Path,
    target: Path,
): Boolean {
    if (link(existing.toString(), target.toString()) == 0) return true
    val error = errno
    if (error == EEXIST) return false
    throw IOException("link failed: errno $error")
}
