package appmonitor.desktop.app.store

import appmonitor.desktop.domain.fittingRules
import appmonitor.desktop.server.DeviceSession
import appmonitor.desktop.storage.Storage
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.MockRule
import appmonitor.v1.MockRules
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Sends [session] its app's active rules once, then again on every change, until cancelled (ADR-0113). */
internal suspend fun pushMocks(
    storage: Storage,
    session: DeviceSession,
    clock: () -> Long = System::currentTimeMillis,
) {
    // ADR-0223: switched off, every device gets an empty rule set; its app's rules wait in the store.
    combine(storage.observeActiveMocks(session.hello.app_id), storage.observeMocking()) { rules, on -> if (on) rules else emptyList() }
        .distinctUntilChanged()
        .collect { rules -> session.send(mockRulesEnvelope(rules, clock())) }
}

/** [rules] as one envelope within [Protocol.MAX_FRAME_BYTES]: the first rule that does not fit and the ones after it are left out. */
internal fun mockRulesEnvelope(
    rules: List<MockRule>,
    atMs: Long,
): Envelope {
    val kept = rules.take(fittingRules(rules))
    return Envelope(
        channel = Protocol.CHANNEL_INTERACTION,
        type = "MockRules",
        payload = MockRules(kept).encodeByteString(),
        timestamp_ms = atMs,
    )
}
