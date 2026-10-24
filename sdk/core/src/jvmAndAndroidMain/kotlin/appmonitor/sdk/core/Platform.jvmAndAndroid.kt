package appmonitor.sdk.core

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.RandomAccessFile

private val fileLockHolder = Any()

// A JVM holds one FileLock per file: a second thread's lock() throws instead of waiting.
internal actual fun <T> withFileLock(
    lock: Path,
    block: () -> T,
): T =
    synchronized(fileLockHolder) {
        RandomAccessFile(lock.toString(), "rw").use { file ->
            val held = file.channel.lock()
            try {
                block()
            } finally {
                held.release()
            }
        }
    }

internal actual fun createParentDirectories(file: Path) {
    file.parent?.let { SystemFileSystem.createDirectories(it) }
}
