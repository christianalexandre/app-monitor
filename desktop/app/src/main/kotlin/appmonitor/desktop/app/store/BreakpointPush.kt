package appmonitor.desktop.app.store

import appmonitor.desktop.domain.fitting
import appmonitor.desktop.server.DeviceSession
import appmonitor.desktop.storage.Storage
import appmonitor.protocol.Protocol
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointRules
import appmonitor.v1.Envelope

/** Sends [session] its app's enabled breakpoints once, then again on every change, until cancelled (ADR-0118). */
internal suspend fun pushBreakpoints(
    storage: Storage,
    session: DeviceSession,
    clock: () -> Long = System::currentTimeMillis,
) {
    storage.observeActiveBreakpoints(session.hello.app_id).collect { rules -> session.send(breakpointRulesEnvelope(rules, clock())) }
}

/** [rules] as one envelope within [Protocol.MAX_FRAME_BYTES], cut as `MockRules` is (ADR-0113 C.1). */
internal fun breakpointRulesEnvelope(
    rules: List<BreakpointRule>,
    atMs: Long,
): Envelope =
    Envelope(
        channel = Protocol.CHANNEL_INTERACTION,
        type = "BreakpointRules",
        payload = BreakpointRules(rules.take(fitting(rules, BreakpointRule.ADAPTER))).encodeByteString(),
        timestamp_ms = atMs,
    )
