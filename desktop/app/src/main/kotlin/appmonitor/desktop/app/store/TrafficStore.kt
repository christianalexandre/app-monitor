package appmonitor.desktop.app.store

import appmonitor.desktop.server.DeviceSession
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** In-memory store the UI observes. Holds everything since the app started. */
internal class TrafficStore {
    private val mutableState = MutableStateFlow(TrafficState())

    val state: StateFlow<TrafficState> = mutableState.asStateFlow()

    /** Collects every session and its envelopes until [sessions] completes and every session closes. */
    suspend fun collect(sessions: Flow<DeviceSession>): Unit =
        coroutineScope {
            sessions.collect { session ->
                mutableState.update { it.connected(session.id, session.hello) }
                launch {
                    try {
                        session.envelopes.collect { envelope -> mutableState.update { it.received(session.id, envelope) } }
                    } finally {
                        mutableState.update { it.disconnected(session.id) }
                    }
                }
            }
        }
}
