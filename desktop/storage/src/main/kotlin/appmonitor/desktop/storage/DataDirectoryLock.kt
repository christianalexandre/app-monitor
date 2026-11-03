package appmonitor.desktop.storage

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** Another store, in this process or another, holds [directory] (ADR-0175). */
class DataDirectoryInUseException(
    val directory: Path,
) : Exception("Another App Monitor is using $directory")

/** Held from a store's open to its close; the OS frees it when the process ends, however it ends. */
internal class DataDirectoryLock private constructor(
    private val channel: FileChannel,
) : AutoCloseable {
    override fun close() = channel.close()

    companion object {
        private const val FILE_NAME = "appmonitor.lock"

        fun acquire(directory: Path): DataDirectoryLock {
            val channel = FileChannel.open(directory.resolve(FILE_NAME), CREATE, WRITE)
            val lock =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } catch (e: Throwable) {
                    channel.close()
                    throw e
                }
            if (lock == null) {
                channel.close()
                throw DataDirectoryInUseException(directory)
            }
            return DataDirectoryLock(channel)
        }
    }
}
