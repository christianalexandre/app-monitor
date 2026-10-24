package appmonitor.sdk.core

import android.annotation.SuppressLint
import android.security.NetworkSecurityPolicy
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Paths

internal actual fun isCleartextPermitted(host: String): Boolean = NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)

internal actual fun platformLog(message: String) {
    Log.w("AppMonitor", message)
}

internal actual val CONNECT_FAILURE_HINT: String = "On Android 17+ check ACCESS_LOCAL_NETWORK"

// java.nio.file is API 26+; host tests run on a JDK, where android.jar's Os is a stub.
@SuppressLint("NewApi")
internal actual fun linkExclusive(
    existing: Path,
    target: Path,
): Boolean =
    try {
        Files.createLink(Paths.get(target.toString()), Paths.get(existing.toString()))
        true
    } catch (_: FileAlreadyExistsException) {
        false
    } catch (_: NoClassDefFoundError) {
        osLink(existing, target)
    }

private fun osLink(
    existing: Path,
    target: Path,
): Boolean =
    try {
        Os.link(existing.toString(), target.toString())
        true
    } catch (e: ErrnoException) {
        if (e.errno != OsConstants.EEXIST) throw e
        false
    }

internal actual fun createParentDirectories(file: Path) {
    file.parent?.let { SystemFileSystem.createDirectories(it) }
}
