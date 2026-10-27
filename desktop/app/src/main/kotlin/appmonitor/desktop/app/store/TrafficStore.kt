package appmonitor.desktop.app.store

import appmonitor.desktop.server.DeviceSession
import appmonitor.desktop.server.STOP_TIMEOUT
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.TrafficSink
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

/** Feeds [sink] with each session's envelopes in order (SDD 09); pushes [storage]'s rules, holds calls, sends replays (SDD 12). */
internal class TrafficStore(
    private val storage: Storage,
    private val sink: TrafficSink,
    val heldCalls: HeldCalls = HeldCalls(),
    val replays: Replays = Replays(),
) {
    /** Collects every session and its envelopes until [sessions] completes and every session closes. */
    suspend fun collect(sessions: Flow<DeviceSession>): Unit =
        coroutineScope {
            sessions.collect { session ->
                sink.connected(session.id, session.hello, session.peerAddress)
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
                            sink.received(session.id, envelope)
                        }
                    } finally {
                        pushes.forEach { it.cancel() }
                        timeouts.cancel()
                        heldCalls.ended(session.id)
                        replays.ended(session.id)
                        // A full queue on a stalled disk must not hold the exit; the next open closes the session.
                        withContext(NonCancellable) { withTimeoutOrNull(STOP_TIMEOUT) { sink.disconnected(session.id) } }
                    }
                }
            }
        }
}
