---
id: ADR-0167
title: The desktop reads device frames over a raw WebSocket session and answers the close after them
status: Accepted
date: 2026-10-31
superseded_by:
---

# ADR-0167 — The desktop reads device frames over a raw WebSocket session and answers the close after them

## Context

PLAN-0015 H5.3 asks, before M10 freezes the protocol, that a frame the
desktop rejects just before the device's close reaches the device as that
rejection (DEBT-0014), and that a lagging collector does not close a
reachable device with 1011 (DEBT-0022). Not reopened: Ktor CIO 3.6.0, the
endpoint, the close codes of ADR-0021, the 15 s ping and timeout, and
reading until the device's close after the desktop's own (H5.2d).

Evidence, Ktor 3.6.0 sources (`ktor-websockets-jvm-3.6.0-sources.jar`) and
the server plugin's bytecode, read on 2026-10-31:

- `DefaultWebSocketSessionImpl.runIncomingProcessor` answers a `Close` frame
  the moment it reads it (`outgoing.send(Frame.Close(...))`) and only queues
  data frames into `incoming`. A test sending 500 envelopes, one envelope on a
  channel not accepted, then its close, to a session whose collector starts
  300 ms later, got 1000 from the old server, not 1002
  (`ServerHardeningTest`, "a frame rejected just before the client's close").
- The server plugin's `channelsConfig` defaults to
  `WebSocketChannelsConfig.UNLIMITED`, and so do `WebSocketReader`'s queue
  and the raw session's `incoming`. Ktor reads the socket ahead of the
  handler with no bound, so ADR-0009's "a slow collector stops reading the
  socket" did not hold on 3.6.0, and pongs were never stuck behind data
  frames: a client answering pings every 100 ms kept its session while 500
  envelopes waited a second for a collector, on the old server too.
- `webSocketRaw(path, negotiateExtensions = false)` hands the handler the
  raw session: no ponger, no pinger, no automatic close, no
  defragmentation; the plugin's `maxFrameSize` still applies, and Ktor
  still queues its own 1009 or 1002 on an oversized or malformed frame.

## Options

1. **Keep `DefaultWebSocketSession`, bound its incoming channel.** Brings
   back ADR-0009's back-pressure, but a full channel stops Ktor's incoming
   processor, pongs included: DEBT-0022 for real. The close is still
   answered at once, so DEBT-0014 stays.
2. **Keep `DefaultWebSocketSession` and add an application close
   handshake.** A new envelope before the close: changes the protocol and
   every SDK, and an older SDK still loses the rejection.
3. **Raw session, the desktop's own `DeviceConnection`.** One reader
   answers pings, hands pongs to its own pinger and queues whole data
   frames in order; the device's close is queued after them, and answered
   once the handler has taken every earlier frame, with the handler's own
   close when it rejected one. No protocol change. About 150 lines that
   Ktor did before: pinger, ponger, defragmentation, close echo.

## Decision

Option 3. `DeviceConnection` reads the raw session; its frame queue stays
unbounded, as Ktor's was, so pongs are read however far the collector lags
(DEBT-0022 paid), and the device's close is answered after the frames
before it (DEBT-0014 paid). A device's close now waits for the collector to
take those frames.

## Consequences

- A device sees the 1002 of a frame it sent just before closing.
- The desktop holds in memory what the device sent and the collector has
  not taken, without a bound, as it already did with Ktor's defaults;
  ADR-0009's TCP back-pressure is not what happens, and SDD 01 says so.
  A bound is DEBT-0113's.
- A device closing while the collector lags waits for its close to be
  answered; the SDKs bound that wait themselves (SDD 04, SDD 06).
- Ping, pong and defragmentation are this module's code, covered by
  `ServerHardeningTest`.
