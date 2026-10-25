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

/** What [IssueRepository.update] changes; a null field keeps what is stored, so two writers editing different fields both land. */
data class IssueChange(
    val title: String? = null,
    val note: String? = null,
    val tags: Collection<String>? = null,
    val status: IssueStatus? = null,
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

    /** An issue from the window linking [exchangeIds]; throws for a blank title or no exchange. */
    suspend fun create(
        title: String,
        note: String,
        tags: Collection<String>,
        exchangeIds: Collection<Long>,
    ): IssueWrite

    /** Throws for a blank title. */
    suspend fun update(
        id: Long,
        change: IssueChange,
    ): IssueWrite

    suspend fun delete(id: Long)
}
