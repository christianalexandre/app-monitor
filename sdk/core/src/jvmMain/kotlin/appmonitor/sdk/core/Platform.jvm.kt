package appmonitor.sdk.core

internal actual fun isCleartextPermitted(host: String): Boolean = true

internal actual fun platformLog(message: String) {
    System.err.println("AppMonitor: $message")
}
