---
id: ADR-0166
title: The device reports what it dropped in a DeliveryReport, the desktop counts what it lost
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-31
superseded_by:
---

# ADR-0166 — The device reports what it dropped in a DeliveryReport

## Context

`sdk/core` and the Swift SDK drop an envelope in three cases (SDD 04,
*Buffer policy*): the oldest one when the 4096-envelope or 8 MiB buffer is
full, one larger than `Protocol.MAX_FRAME_BYTES` before it enters the
buffer, and those written to a socket that then dies, since an envelope
leaves the buffer once written and is never resent. Both SDKs already
counted the first two (`EventBuffer.dropped`, the client's oversize
counter) for tests only; the desktop could not tell a lost event from one
never sent (DEBT-0023). PLAN-0015 H5.3 settles the wire before M10
publishes the SDKs: a message an old SDK does not send is cheap now.

Only the device knows the first two counts. Only the desktop knows what it
read. Nothing acknowledges a frame: the SDK knows what it wrote, not what
arrived.

## Options

1. **A `DeliveryReport` on `core`: the device's two counts, and the frames
   it wrote on its previous session; the desktop subtracts what it read on
   that session.** The SDK sends one right after `HelloAck`, before any
   buffered envelope, and again before the next envelope whenever a count
   grew. Counts run from the SDK's start (one `MonitorClient`), so a lost
   report loses nothing: the next one carries the total. The desktop keeps,
   per closed session, the frames it read and the lost total so far, for
   the 1024 latest sessions; lost = that total plus the previous session's
   written frames it never read. No acknowledgement, no new state on the
   SDK beyond two numbers and a session id.
2. **A sequence number on every `Envelope`.** Gaps show every loss, the
   oldest-first evictions included, but the field rides on every frame,
   the desktop must track a sequence per SDK start, and a gap cannot say
   why. The evictions and the oversized ones still need counts.
3. **Acknowledgements from the desktop.** The SDK would know what arrived
   and could resend; that reverses ADR-0022's "at most once" and adds a
   window to both SDKs. Out of proportion to showing a number.

## Decision

Option 1. `core.proto` gains `DeliveryReport { int64 evicted; int64
oversized; string previous_session_id; int64 previous_session_written }`,
`int64` as every count of the protocol, which the Swift generator reads
(ADR-0160). A frame counts as written once handed to the socket (Ktor's
`send`, `NWConnection.send`), reports included; the desktop counts every
frame it reads after `Hello`. `AppMonitorServer` exposes the result as
`DeviceSession.drops` (`Drops(evicted, oversized, lost)`), null until the
session's first report (an SDK without them); `lost` is null when the
previous session is not among those it kept (another desktop, or this one
restarted). The report also reaches `DeviceSession.envelopes`, so the store
keeps it like any other `core` envelope. The headless server logs
`drops: evicted N, oversized N, lost N`, which the end to end scripts
require to be all zero.

No public API changes: an app developer sets nothing; the SDKs' `dropped`
stays internal.

## Consequences

- An older desktop ignores the unknown `core` type; an older SDK sends no
  report, and its sessions show no drops rather than zero.
- `lost` is a lower bound when the socket dies mid-frame on the desktop's
  side and a frame is read but not decoded: that frame counts as read.
- Drops on the last session of an SDK start are never reported: a stopped
  client has no next session.
- The desktop window does not show `drops` yet: PLAN-0015 H5.3c stores and
  shows them (DEBT-0023 stays open until then).
- Tests that count what a stand-in desktop received set reports apart
  (`StandInDesktop.reports`, `TestDesktop.events`).
