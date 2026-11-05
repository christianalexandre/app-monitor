package appmonitor.desktop.storage

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap

/** Another store, in this process or another, holds [directory] (ADR-0175). */
class DataDirectoryInUseException(
    val directory: Path,
) : Exception("Another App Monitor is using $directory")

/** Held from a store's open to its close; the OS frees it when the process ends, however it ends. */
internal class DataDirectoryLock private constructor(
    private val channel: FileChannel,
    private val directory: Path,
) : AutoCloseable {
    // In this order: the directory free before the channel closed would let an open's lock be dropped with it.
    override fun close() {
        try {
            channel.close()
        } finally {
            held.remove(directory)
        }
    }

    companion object {
        private const val FILE_NAME = "appmonitor.lock"

        // POSIX drops a process's lock when any of its channels on the file closes: refuse before opening a second.
        private val held = ConcurrentHashMap.newKeySet<Path>()

        fun acquire(directory: Path): DataDirectoryLock {
            val realDirectory = directory.toRealPath()
            if (!held.add(realDirectory)) throw DataDirectoryInUseException(directory)
            try {
                val channel = FileChannel.open(realDirectory.resolve(FILE_NAME), CREATE, WRITE)
                val lock =
                    try {
                        channel.tryLock()
                    } catch (e: Throwable) {
                        channel.close()
                        throw e
                    }
                if (lock == null) {
                    channel.close()
                    throw DataDirectoryInUseException(directory)
                }
                return DataDirectoryLock(channel, realDirectory)
            } catch (e: Throwable) {
                held.remove(realDirectory)
                throw e
            }
        }
    }
}
