package appmonitor.desktop.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the window takes in what the devices send (ADR-0222). Paused, a request that starts is neither listed nor
 * stored; sessions, approvals, mocks and breakpoints go on.
 */
interface MonitorRepository {
    val paused: StateFlow<Boolean>

    fun pause()

    fun resume()
}
