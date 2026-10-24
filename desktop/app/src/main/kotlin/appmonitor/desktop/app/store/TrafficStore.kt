package appmonitor.desktop.app.store

import appmonitor.desktop.server.DeviceSession
import appmonitor.desktop.server.STOP_TIMEOUT
import appmonitor.desktop.storage.Storage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Feeds [storage] with every session and its envelopes, in the order each session produced them (SDD 09). */
internal class TrafficStore(
    private val storage: Storage,
) {
    /** Collects every session and its envelopes until [sessions] completes and every session closes. */
    suspend fun collect(sessions: Flow<DeviceSession>): Unit =
        coroutineScope {
            sessions.collect { session ->
                storage.connected(session.id, session.hello, session.peerAddress)
                launch {
                    try {
                        session.envelopes.collect { storage.received(session.id, it) }
                    } finally {
                        // A full queue on a stalled disk must not hold the exit; the next open closes the session.
                        withContext(NonCancellable) { withTimeoutOrNull(STOP_TIMEOUT) { storage.disconnected(session.id) } }
                    }
                }
            }
        }
}
