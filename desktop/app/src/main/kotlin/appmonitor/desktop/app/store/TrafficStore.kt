package appmonitor.desktop.app.store

import appmonitor.desktop.server.DeviceSession
import appmonitor.desktop.server.STOP_TIMEOUT
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StoredDrops
import appmonitor.desktop.storage.TrafficSink
import appmonitor.protocol.Protocol
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Feeds [sink] with each session's envelopes in order; pushes [storage]'s rules, holds calls, sends replays. */
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
                    var reported: StoredDrops? = null
                    val dropsLock = Mutex()
                    val storeDrops =
                        suspend {
                            dropsLock.withLock {
                                session.drops.value
                                    ?.let {
                                        StoredDrops(
                                            it.evicted,
                                            it.oversized,
                                            it.lost,
                                        )
                                    }?.takeIf { it != reported }
                                    ?.let {
                                        reported = it
                                        sink.dropped(session.id, it)
                                    }
                            }
                        }
                    // A lost count settles when the previous session closes, with no envelope to carry it (ADR-0172).
                    val settledLost =
                        launch {
                            session.drops.first { it?.lost != null }
                            storeDrops()
                        }
                    try {
                        session.envelopes.collect { envelope ->
                            // The server sets drops before it delivers their report: read here, they stay in order (ADR-0166).
                            storeDrops()
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
                        settledLost.cancel()
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
