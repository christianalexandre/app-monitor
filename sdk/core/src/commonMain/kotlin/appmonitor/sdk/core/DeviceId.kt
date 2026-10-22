package appmonitor.sdk.core

import java.io.File
import java.util.UUID

/** The install's device id kept in [file]; a missing or unreadable one is replaced by a new random UUID (ADR-0018 D). */
internal fun deviceId(file: File): String {
    val stored = runCatching { file.readText().trim() }.getOrNull()
    if (stored != null && runCatching { UUID.fromString(stored) }.isSuccess) return stored
    val id = UUID.randomUUID().toString()
    runCatching {
        file.parentFile?.mkdirs()
        file.writeText(id)
    }
    return id
}
