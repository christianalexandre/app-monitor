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
): String = firstWritten(file, Uuid.random().toString(), link)

/** The UUID kept in [file], or [candidate] written there when it holds none: the first writer wins. */
private fun firstWritten(
    file: Path,
    candidate: String,
    link: (existing: Path, target: Path) -> Boolean? = ::linkExclusive,
): String {
    storedDeviceId(file)?.let { return it }
    runCatching { publish(candidate, file, link) }
    return storedDeviceId(file) ?: candidate
}

/** The desktop a device trusts once one approved it (ADR-0168); null [pinned] until then. */
internal interface DesktopPin {
    fun pinned(): String?

    /** Pins [desktopId] unless another is already; returns the one pinned. */
    fun pin(desktopId: String): String
}

/** [DesktopPin] kept in [file], beside the device id and shared with the Swift SDK on iOS. */
internal class FileDesktopPin(
    private val file: Path,
) : DesktopPin {
    override fun pinned(): String? = storedDeviceId(file)

    override fun pin(desktopId: String): String = firstWritten(file, desktopId)
}

/** The pin file's name, beside the device id's in both SDKs (ADR-0168). */
internal const val DESKTOP_PIN_FILE_NAME: String = "appmonitor-desktop-id"

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
