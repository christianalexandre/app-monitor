package appmonitor.sdk.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.posix.EEXIST
import platform.posix.LOCK_EX
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.errno
import platform.posix.flock
import platform.posix.link
import platform.posix.open

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
): Boolean? {
    if (link(existing.toString(), target.toString()) == 0) return true
    return if (errno == EEXIST) false else null
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun <T> withFileLock(
    lock: Path,
    block: () -> T,
): T {
    val fd = open(lock.toString(), O_CREAT or O_RDWR, S_IRUSR or S_IWUSR)
    if (fd < 0) throw IOException("cannot open $lock: errno $errno")
    try {
        if (flock(fd, LOCK_EX) != 0) throw IOException("cannot lock $lock: errno $errno")
        return block()
    } finally {
        close(fd)
    }
}

// ADR-0079: not Path.parent nor createDirectories, whose dirname(3) races.
@OptIn(ExperimentalForeignApi::class)
internal actual fun createParentDirectories(file: Path) {
    val parent = file.toString().substringBeforeLast('/', "")
    if (parent.isEmpty()) return
    if (!NSFileManager.defaultManager.createDirectoryAtPath(parent, withIntermediateDirectories = true, attributes = null, error = null)) {
        throw IOException("cannot create $parent")
    }
}
