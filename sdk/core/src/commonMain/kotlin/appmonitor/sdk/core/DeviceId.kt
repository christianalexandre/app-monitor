package appmonitor.sdk.core

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.uuid.Uuid

/** The install's device id kept in [file]; a missing or unreadable one is replaced by a new random UUID (ADR-0018 D). */
internal fun deviceId(
    file: Path,
    link: (existing: Path, target: Path) -> Boolean? = ::linkExclusive,
): String {
    storedDeviceId(file)?.let { return it }
    val id = Uuid.random().toString()
    runCatching { publish(id, file, link) }
    return storedDeviceId(file) ?: id
}

/** Hard links [id], written whole beside [file], to it, or moves it there under a file lock without links: the first writer wins (ADR-0078, ADR-0080). */
private fun publish(
    id: String,
    file: Path,
    link: (existing: Path, target: Path) -> Boolean?,
) {
    runCatching { createParentDirectories(file) }
    val temp = Path("$file.${Uuid.random()}")
    try {
        SystemFileSystem.sink(temp).buffered().use { it.writeString(id) }
        when (runCatching { link(temp, file) }.getOrNull()) {
            true -> Unit
            false -> if (storedDeviceId(file) == null) SystemFileSystem.atomicMove(temp, file)
            null -> withFileLock(Path("$file.lock")) { if (storedDeviceId(file) == null) SystemFileSystem.atomicMove(temp, file) }
        }
    } finally {
        SystemFileSystem.delete(temp, mustExist = false)
    }
}

private fun storedDeviceId(file: Path): String? {
    val stored = runCatching { SystemFileSystem.source(file).buffered().use { it.readString().trim() } }.getOrNull()
    return stored?.takeIf { runCatching { Uuid.parse(it) }.isSuccess }
}

/** Creates [file]'s missing parent directories; may throw when a racing writer creates them first. */
internal expect fun createParentDirectories(file: Path)

/** Creates [target] as a hard link to [existing]; false when [target] already exists, null when the platform or file system cannot link. */
internal expect fun linkExclusive(
    existing: Path,
    target: Path,
): Boolean?

/** Runs [block] holding an exclusive lock on [lock], created if missing, against other threads and processes. */
internal expect fun <T> withFileLock(
    lock: Path,
    block: () -> T,
): T
