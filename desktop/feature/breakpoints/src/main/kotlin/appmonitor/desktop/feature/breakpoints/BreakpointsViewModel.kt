package appmonitor.desktop.feature.breakpoints

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import appmonitor.desktop.domain.Breakpoint
import appmonitor.desktop.domain.BreakpointChange
import appmonitor.desktop.domain.BreakpointRepository
import appmonitor.desktop.domain.BreakpointWrite
import appmonitor.desktop.domain.HeldCall
import appmonitor.desktop.domain.HeldCallRepository
import appmonitor.desktop.domain.HeldEdit
import appmonitor.v1.BreakpointRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Every app's breakpoints in match order, and the calls devices hold now, oldest first. */
data class BreakpointsState(
    val breakpoints: List<Breakpoint> = emptyList(),
    val held: List<HeldCall> = emptyList(),
)

/** The breakpoints the window lists and edits, and the calls they hold (ADR-0119); no [heldCalls], nothing is held. */
class BreakpointsViewModel(
    private val breakpoints: BreakpointRepository,
    private val heldCalls: HeldCallRepository?,
) : ViewModel() {
    val state: StateFlow<BreakpointsState> =
        combine(breakpoints.observeBreakpoints(), heldCalls?.held ?: MutableStateFlow(emptyList()), ::BreakpointsState)
            .stateIn(viewModelScope, SharingStarted.Eagerly, BreakpointsState(held = heldCalls?.held?.value.orEmpty()))

    val maxTimeoutMs: Long get() = breakpoints.maxTimeoutMs

    fun toggle(breakpoint: Breakpoint) {
        viewModelScope.launch { breakpoints.update(breakpoint.id, BreakpointChange(enabled = !breakpoint.enabled)) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { breakpoints.delete(id) }
    }

    /** Creates a breakpoint, or changes [stored]'s name and rule, leaving `enabled` to the list; null once saved, else why not. */
    suspend fun save(
        stored: Breakpoint?,
        appId: String,
        name: String,
        rule: BreakpointRule,
    ): String? {
        val write =
            if (stored == null) {
                breakpoints.create(appId, name, rule)
            } else {
                breakpoints.update(stored.id, BreakpointChange(name = name, rule = rule))
            }
        return when (write) {
            is BreakpointWrite.Saved -> null
            BreakpointWrite.UnknownBreakpoint -> "Not saved: the breakpoint was deleted"
            BreakpointWrite.NotSaved -> "Not saved: the store could not write it"
        }
    }

    fun resume(
        call: HeldCall,
        edit: HeldEdit,
    ) {
        heldCalls?.resume(call, edit)
    }

    fun abort(
        call: HeldCall,
        message: String,
    ) {
        heldCalls?.abort(call, message)
    }
}
