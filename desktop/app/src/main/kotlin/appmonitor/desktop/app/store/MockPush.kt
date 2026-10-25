package appmonitor.desktop.app.store

import appmonitor.desktop.server.DeviceSession
import appmonitor.desktop.storage.Storage
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.MockRule
import appmonitor.v1.MockRules
import com.squareup.wire.ProtoAdapter

/** Sends [session] its app's active rules once, then again on every change, until cancelled (ADR-0113). */
internal suspend fun pushMocks(
    storage: Storage,
    session: DeviceSession,
    clock: () -> Long = System::currentTimeMillis,
) {
    storage.observeActiveMocks(session.hello.app_id).collect { rules -> session.send(mockRulesEnvelope(rules, clock())) }
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

/** How many of [rules], from the first, one `MockRules` envelope holds (ADR-0113 C.1); the window marks the others. */
internal fun fittingRules(rules: List<MockRule>): Int = fitting(rules, MockRule.ADAPTER)

/** How many of [rules], from the first, fit one envelope as field 1 of a rules message. */
internal fun <T> fitting(
    rules: List<T>,
    adapter: ProtoAdapter<T>,
): Int {
    var size = 0L
    return rules
        .takeWhile { rule ->
            size += adapter.encodedSizeWithTag(1, rule)
            size <= MAX_RULES_BYTES
        }.size
}

// Room for the envelope's own fields and the payload's length.
private const val MAX_RULES_BYTES = Protocol.MAX_FRAME_BYTES - 1_024
