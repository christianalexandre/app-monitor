package appmonitor.desktop.feature.mocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import appmonitor.desktop.domain.Mock
import appmonitor.desktop.domain.MockChange
import appmonitor.desktop.domain.MockRepository
import appmonitor.desktop.domain.MockSource
import appmonitor.desktop.domain.MockWrite
import appmonitor.desktop.domain.fittingRules
import appmonitor.v1.MockRule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Every app's rules in match order, and the ids of active ones a device is not sent ([cut], ADR-0113 C.1). */
data class MocksState(
    val mocks: List<Mock> = emptyList(),
    val cut: Set<Long> = emptySet(),
) {
    /** Rules from MCP waiting for the developer's confirmation (ADR-0110 F.1). */
    val pending: Int get() = mocks.count { it.pending }
}

/** The mock rules the window lists and edits (ADR-0114). */
class MocksViewModel(
    private val mocks: MockRepository,
) : ViewModel() {
    val state: StateFlow<MocksState> =
        mocks
            .observeMocks()
            .map { MocksState(it, cutOf(it)) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, MocksState())

    fun confirm(id: Long) {
        viewModelScope.launch { mocks.confirm(id) }
    }

    fun toggle(mock: Mock) {
        viewModelScope.launch { mocks.update(mock.id, MockChange(enabled = !mock.enabled)) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { mocks.delete(id) }
    }

    /** Creates a rule, or changes [stored]'s name and rule, leaving `enabled` to the list; null once saved, else why not. */
    suspend fun save(
        stored: Mock?,
        appId: String,
        name: String,
        rule: MockRule,
    ): String? {
        val write =
            if (stored == null) {
                mocks.create(appId, name, rule, MockSource.WINDOW)
            } else {
                mocks.update(stored.id, MockChange(name = name, rule = rule))
            }
        return when (write) {
            is MockWrite.Saved -> null
            MockWrite.UnknownMock -> "Not saved: the rule was deleted"
            MockWrite.NotSaved -> "Not saved: the store could not write it"
        }
    }
}

private fun cutOf(mocks: List<Mock>): Set<Long> =
    mocks
        .filter { it.active }
        .groupBy { it.appId }
        .values
        .flatMap { active -> active.drop(fittingRules(active.map { it.rule })) }
        .map { it.id }
        .toSet()
