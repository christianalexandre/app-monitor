package appmonitor.desktop.storage

import appmonitor.desktop.storage.db.AppMonitorDatabase

/** How long traffic stays (ADR-0082 A.1): [maxAgeDays] null keeps it whatever its age; [maxBytes] bounds the file. */
data class RetentionSettings(
    val maxAgeDays: Int? = DEFAULT_MAX_AGE_DAYS,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    companion object {
        const val DEFAULT_MAX_AGE_DAYS = 7
        const val DEFAULT_MAX_BYTES = 1L shl 30
    }
}

/**
 * What the last retention pass left. [usedBytes] is the file's size; [keptOverBound] is true when what retention keeps
 * (named runs, connected sessions) still passes [RetentionSettings.maxBytes] (ADR-0082 C.1).
 */
data class RetentionStatus(
    val usedBytes: Long = 0,
    val keptOverBound: Boolean = false,
    val lastPassAtMs: Long? = null,
    val deletedSessions: Long = 0,
    val lastError: String? = null,
)

internal fun AppMonitorDatabase.retentionSettings(): RetentionSettings {
    fun valueOf(key: String) = settingQueries.valueOf(key).executeAsOneOrNull()
    val age = valueOf(KEY_MAX_AGE_DAYS)
    return RetentionSettings(
        maxAgeDays =
            when (age) {
                null -> RetentionSettings.DEFAULT_MAX_AGE_DAYS
                NEVER -> null
                else -> age.toIntOrNull()?.takeIf { it > 0 } ?: RetentionSettings.DEFAULT_MAX_AGE_DAYS
            },
        maxBytes = valueOf(KEY_MAX_BYTES)?.toLongOrNull()?.takeIf { it > 0 } ?: RetentionSettings.DEFAULT_MAX_BYTES,
    )
}

internal fun AppMonitorDatabase.putRetentionSettings(settings: RetentionSettings) {
    settingQueries.put(KEY_MAX_AGE_DAYS, settings.maxAgeDays?.toString() ?: NEVER)
    settingQueries.put(KEY_MAX_BYTES, settings.maxBytes.toString())
}

private const val KEY_MAX_AGE_DAYS = "retention.max_age_days"
private const val KEY_MAX_BYTES = "retention.max_bytes"
private const val NEVER = "never"
