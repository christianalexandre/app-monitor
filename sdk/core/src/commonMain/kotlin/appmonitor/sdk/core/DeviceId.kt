package appmonitor.sdk.core

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.uuid.Uuid

/** The install's device id kept in [file]; a missing or unreadable one is replaced by a new random UUID (ADR-0018 D). */
internal fun deviceId(file: Path): String {
    storedDeviceId(file)?.let { return it }
    val id = Uuid.random().toString()
    runCatching { publish(id, file) }
    return storedDeviceId(file) ?: id
}

/** Hard links [id], written whole beside [file], to it: the first writer wins, the Swift SDK's included (ADR-0078). */
private fun publish(
    id: String,
    file: Path,
) {
    runCatching { createParentDirectories(file) }
    val temp = Path("$file.${Uuid.random()}")
    try {
        SystemFileSystem.sink(temp).buffered().use { it.writeString(id) }
        val linked = runCatching { linkExclusive(temp, file) }.getOrNull()
        if (linked == null || (!linked && storedDeviceId(file) == null)) SystemFileSystem.atomicMove(temp, file)
    } finally {
        SystemFileSystem.delete(temp, mustExist = false)
    }
}

private fun storedDeviceId(file: Path): String? {
    val stored = runCatching { SystemFileSystem.source(file).buffered().use { it.readString().trim() } }.getOrNull()
    return stored?.takeIf { runCatching { Uuid.parse(it) }.isSuccess }
}

/** Creates [file]'s missing parent directories; a racing writer creating them too is not an error. */
internal expect fun createParentDirectories(file: Path)

/** Creates [target] as a hard link to [existing]; false when [target] already exists, throws when the file system cannot link. */
internal expect fun linkExclusive(
    existing: Path,
    target: Path,
): Boolean
