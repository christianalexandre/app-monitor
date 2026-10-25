package appmonitor.desktop.storage

import appmonitor.desktop.storage.db.Filtered

/** ADR-0095 A.1: an issue is to look at, or done. */
enum class IssueStatus(
    internal val stored: String,
) {
    OPEN("open"),
    RESOLVED("resolved"),
    ;

    internal companion object {
        fun of(stored: String): IssueStatus = entries.first { it.stored == stored }
    }
}

/** Who created an issue (ADR-0095 C.1). */
enum class IssueSource(
    internal val stored: String,
) {
    WINDOW("window"),
    MCP("mcp"),
    ;

    internal companion object {
        fun of(stored: String): IssueSource = entries.first { it.stored == stored }
    }
}

/** An issue as the list shows it; [tags] sorted, [exchangeCount] the exchanges it links. */
data class StoredIssue(
    val id: Long,
    val title: String,
    val note: String,
    val tags: List<String>,
    val status: IssueStatus,
    val source: IssueSource,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val exchangeCount: Long,
)

/** Unset fields match all, set ones combine with AND. */
data class IssueFilter(
    val status: IssueStatus? = null,
    val tag: String? = null,
)

/**
 * What [Storage.updateIssue] changes; a null field keeps what is stored, so two writers editing different fields both
 * land. [tags] and [exchangeIds] replace the stored ones.
 */
data class IssueChange(
    val title: String? = null,
    val note: String? = null,
    val tags: Collection<String>? = null,
    val status: IssueStatus? = null,
    val exchangeIds: Collection<Long>? = null,
)

/** What a queued issue write did, known once its batch is committed or lost. */
sealed interface IssueWrite {
    data class Saved(
        val id: Long,
    ) : IssueWrite

    /** Nothing written: these exchanges were not stored when the write reached the writer. */
    data class UnknownExchanges(
        val ids: List<Long>,
    ) : IssueWrite

    /** Nothing written: the issue was deleted, or never stored. */
    data object UnknownIssue : IssueWrite

    /** Its batch was lost, or the store was closing. */
    data object NotSaved : IssueWrite
}

/** Trimmed, blank ones dropped, line breaks turned to spaces (the list query joins tags with them), each once. */
internal fun normalizedTags(tags: Collection<String>): List<String> =
    tags
        .map { tag -> tag.lines().joinToString(" ").trim() }
        .filter { it.isNotEmpty() }
        .distinct()

internal fun Filtered.toStored() =
    StoredIssue(
        id = id,
        title = title,
        note = note,
        tags = tags?.split('\n')?.sorted().orEmpty(),
        status = IssueStatus.of(status),
        source = IssueSource.of(source),
        createdAtMs = created_at_ms,
        updatedAtMs = updated_at_ms,
        exchangeCount = exchange_count,
    )
