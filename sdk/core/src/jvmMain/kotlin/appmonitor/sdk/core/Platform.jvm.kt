package appmonitor.sdk.core

import kotlinx.io.files.Path
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Paths

internal actual fun isCleartextPermitted(host: String): Boolean = true

internal actual fun platformLog(message: String) {
    System.err.println("AppMonitor: $message")
}

internal actual val CONNECT_FAILURE_HINT: String = "Check that the desktop app is running"

internal actual fun linkExclusive(
    existing: Path,
    target: Path,
): Boolean =
    try {
        Files.createLink(Paths.get(target.toString()), Paths.get(existing.toString()))
        true
    } catch (_: FileAlreadyExistsException) {
        false
    }
