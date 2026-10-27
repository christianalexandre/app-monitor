package appmonitor.desktop.feature.traffic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import appmonitor.desktop.domain.ApprovalRepository
import appmonitor.desktop.domain.AskedReplay
import appmonitor.desktop.domain.AskedReplayRepository
import appmonitor.desktop.domain.PendingApproval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What waits for the developer's answer, oldest first: devices to admit and replays an agent asked. */
data class DecisionsState(
    val devices: List<PendingApproval> = emptyList(),
    val replays: List<AskedReplay> = emptyList(),
)

/** The window's questions (ADR-0083, ADR-0121); no [approvals] or [asked], nothing of that kind waits. */
class DecisionsViewModel(
    private val approvals: ApprovalRepository?,
    private val asked: AskedReplayRepository?,
) : ViewModel() {
    val state: StateFlow<DecisionsState> =
        combine(
            approvals?.pending?.onStart { emit(emptyList()) } ?: flowOf(emptyList()),
            asked?.asked ?: MutableStateFlow(emptyList()),
            ::DecisionsState,
        ).stateIn(viewModelScope, SharingStarted.Eagerly, DecisionsState(replays = asked?.asked?.value.orEmpty()))

    fun decide(
        device: PendingApproval,
        approved: Boolean,
    ) {
        approvals?.let { viewModelScope.launch { it.decide(device, approved) } }
    }

    fun answer(
        replay: AskedReplay,
        send: Boolean,
    ) {
        asked?.answer(replay, send)
    }
}
