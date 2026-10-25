package appmonitor.desktop.app.store

import appmonitor.desktop.server.DeviceSession
import appmonitor.desktop.server.STOP_TIMEOUT
import appmonitor.desktop.storage.Storage
import appmonitor.protocol.Protocol
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Feeds [storage] with every session and its envelopes, in the order each session produced them (SDD 09), sends each
 * session that accepted `interaction` its app's mock rules and breakpoints, keeps the calls it holds in [heldCalls] and
 * sends it the [replays] asked for (SDD 12).
 */
internal class TrafficStore(
    private val storage: Storage,
    val heldCalls: HeldCalls = HeldCalls(),
    val replays: Replays = Replays(),
) {
    /** Collects every session and its envelopes until [sessions] completes and every session closes. */
    suspend fun collect(sessions: Flow<DeviceSession>): Unit =
        coroutineScope {
            sessions.collect { session ->
                storage.connected(session.id, session.hello, session.peerAddress)
                launch {
                    val pushes =
                        if (Protocol.CHANNEL_INTERACTION in session.channels) {
                            listOf(launch { pushMocks(storage, session) }, launch { pushBreakpoints(storage, session) })
                        } else {
                            emptyList()
                        }
                    val timeouts = Job(coroutineContext.job)
                    replays.opened(session)
                    try {
                        session.envelopes.collect { envelope ->
                            heldCalls.hold(session, envelope)?.let { call ->
                                launch(timeouts) {
                                    delay(call.hit.timeout_ms)
                                    heldCalls.expire(call)
                                }
                            }
                            replays.read(session, envelope)
                            storage.received(session.id, envelope)
                        }
                    } finally {
                        pushes.forEach { it.cancel() }
                        timeouts.cancel()
                        heldCalls.ended(session.id)
                        replays.ended(session.id)
                        // A full queue on a stalled disk must not hold the exit; the next open closes the session.
                        withContext(NonCancellable) { withTimeoutOrNull(STOP_TIMEOUT) { storage.disconnected(session.id) } }
                    }
                }
            }
        }
}
