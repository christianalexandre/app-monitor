package appmonitor.sdk.core

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.uuid.Uuid

/** The install's device id kept in [file]; a missing or unreadable one is replaced by a new random UUID (ADR-0018 D). */
internal fun deviceId(file: Path): String {
    val stored = runCatching { SystemFileSystem.source(file).buffered().use { it.readString().trim() } }.getOrNull()
    if (stored != null && runCatching { Uuid.parse(stored) }.isSuccess) return stored
    val id = Uuid.random().toString()
    runCatching {
        file.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(file).buffered().use { it.writeString(id) }
    }
    return id
}
