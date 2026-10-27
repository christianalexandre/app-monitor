package appmonitor.desktop.domain

import kotlinx.coroutines.flow.Flow

/** ADR-0095 A.1: an issue is to look at, or done. */
enum class IssueStatus {
    OPEN,
    RESOLVED,
}

/** Who created an issue (ADR-0095 C.1). */
enum class IssueSource {
    WINDOW,
    MCP,
}

/** A stored issue; [tags] sorted, [exchangeCount] the exchanges it links. */
data class Issue(
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
) {
    fun matches(issue: Issue): Boolean = (status == null || issue.status == status) && (tag == null || tag in issue.tags)
}

/** [issue] with the ids of the exchanges it links, ascending. */
data class IssueDetail(
    val issue: Issue,
    val exchangeIds: List<Long>,
)

/**
 * What [IssueRepository.update] changes; a null field keeps what is stored, so two writers editing different fields both
 * land. [tags] and [exchangeIds] replace the stored ones.
 */
data class IssueChange(
    val title: String? = null,
    val note: String? = null,
    val tags: Collection<String>? = null,
    val status: IssueStatus? = null,
    val exchangeIds: Collection<Long>? = null,
)

/** What an issue write did, known once its batch is committed or lost. */
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

/** The issues the window lists and edits; `save_issue` writes the same store (ADR-0095). */
interface IssueRepository {
    /** Every issue, newest first, again after each change. */
    fun observeIssues(): Flow<List<Issue>>

    /** Every tag an issue holds, sorted. */
    fun observeTags(): Flow<List<String>>

    /** The exchanges issue [id] links, in arrival order. */
    suspend fun exchanges(id: Long): List<Exchange>

    /** The issues [filter] matches, newest first, below [beforeId]; the next page passes the last issue's id. */
    suspend fun page(
        filter: IssueFilter,
        beforeId: Long,
        limit: Long,
    ): List<IssueDetail>

    /** An issue from [source] linking [exchangeIds]; throws for a blank title or no exchange. */
    suspend fun create(
        title: String,
        note: String,
        tags: Collection<String>,
        exchangeIds: Collection<Long>,
        source: IssueSource = IssueSource.WINDOW,
        status: IssueStatus = IssueStatus.OPEN,
    ): IssueWrite

    /** Throws for a blank title or an empty [IssueChange.exchangeIds]. */
    suspend fun update(
        id: Long,
        change: IssueChange,
    ): IssueWrite

    suspend fun delete(id: Long)
}
