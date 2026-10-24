package appmonitor.sdk.core

import platform.Foundation.NSLog

/** iOS has no per-host cleartext policy CIO's sockets would obey; ATS governs `URLSession` only (ADR-0066 A). */
internal actual fun isCleartextPermitted(host: String): Boolean = true

internal actual fun platformLog(message: String) {
    NSLog("AppMonitor: %@", message)
}

// ADR-0063 B.1
internal actual val CONNECT_FAILURE_HINT: String =
    "On a device check NSLocalNetworkUsageDescription and NSBonjourServices in Info.plist"
