package appmonitor.desktop.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.Issue
import appmonitor.desktop.domain.IssueChange
import appmonitor.desktop.domain.IssueFilter
import appmonitor.desktop.domain.IssueRepository
import appmonitor.desktop.domain.IssueWrite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Every issue newest first in [all], the ones [filter] keeps in [issues]; [saving] while a write runs. */
data class IssuesState(
    val filter: IssueFilter = IssueFilter(),
    val all: List<Issue> = emptyList(),
    val tags: List<String> = emptyList(),
    val saving: Boolean = false,
) {
    val issues: List<Issue> get() = all.filter(filter::matches)
}

/** The issues the window lists, saves and edits (ADR-0095 A.1). */
class IssuesViewModel(
    private val issues: IssueRepository,
) : ViewModel() {
    private val filter = MutableStateFlow(IssueFilter())
    private val saving = MutableStateFlow(false)

    val state: StateFlow<IssuesState> =
        combine(issues.observeIssues(), issues.observeTags(), filter, saving) { all, tags, filter, saving ->
            IssuesState(filter, all, tags, saving)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, IssuesState())

    fun setFilter(filter: IssueFilter) {
        this.filter.value = filter
    }

    suspend fun exchanges(id: Long): List<Exchange> = issues.exchanges(id)

    /** An issue linking [exchangeIds]; null, and nothing written, while another write runs. */
    suspend fun create(
        title: String,
        note: String,
        tags: List<String>,
        exchangeIds: Set<Long>,
    ): IssueWrite? = guarded { issues.create(title, note, tags, exchangeIds) }

    /** As [create], for [change] to issue [id]. */
    suspend fun update(
        id: Long,
        change: IssueChange,
    ): IssueWrite? = guarded { issues.update(id, change) }

    fun delete(id: Long) {
        viewModelScope.launch { issues.delete(id) }
    }

    private suspend fun guarded(write: suspend () -> IssueWrite): IssueWrite? {
        if (!saving.compareAndSet(false, true)) return null
        try {
            return write()
        } finally {
            saving.value = false
        }
    }
}
