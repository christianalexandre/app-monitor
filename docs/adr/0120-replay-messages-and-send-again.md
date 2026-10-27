---
id: ADR-0120
title: Replay named by request id, refused by the device, linked within its session
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0120 — Replay named by request id, refused by the device, linked within its session

## Context

ADR-0111 D.1 decided that a replay goes through the app: the desktop
sends `ReplayRequest(request_id)`, the SDK sends that call again through
the app's own client, from the last 200 requests it keeps, refuses an
older one or one of a past session "with a reason", and captures the new
call with `replay_of`. E.1 allows replay from the window and from MCP
under ADR-0005's confirmation. It left the wire shape, the storage and
the desktop's side to PLAN-0010 M9.9; the SDKs build on it in M9.10 and
M9.11.

Already decided, not reopened here:

- The `interaction` channel and its whole-set pushes (ADR-0113, ADR-0118).
- A request id is unique per device (`network.proto`), and a session is
  one device's connection.

Evidence, 2026-10-25:

- `RequestStarted` already uses proto3 `optional` for a field absent on
  most calls (`mock_rule_id`, Wire 7.1.0 nullable, swift-protobuf
  `has…`).
- `DeviceSession.send` refuses an envelope once the session closed or on
  a channel not in `HelloAck` (SDD 12), so a desktop cannot send to a past
  session.
- An exchange row is unique by `(session_ref, request_id)` and
  `exchanges.idOf` reads it (`Exchange.sq`).

## Options

### A. How the device says no

1. **A `ReplayRefused(request_id, reason)` message, device to desktop.**
   The window shows the reason under the exchange. Defined now, so the
   fixtures change once and both SDKs send the same thing.
2. **No message: the replay just does not appear.** The developer cannot
   tell "not kept any more" from "still running".

### B. What `replay_of` holds and how it is linked

1. **The original's request id, linked in the same session.** The store
   keeps it as captured (`exchanges.replay_of`, migration `6.sqm`); the
   detail resolves the original's row through `(session_ref,
   replay_of)`, null once retention or an issue-less delete removed it.
2. **The desktop's exchange id.** The device does not know it, and the
   desktop would have to rewrite the captured event.

### C. Where "Send again" may send

1. **Only to the exchange's own session, while it is connected and
   accepted `interaction`.** The window says why it did not send
   otherwise ("the app's session ended", "this app's SDK does not take
   replays"). Exchanges from the desktop's proxy have no device and show
   no button.
2. **To any live session of the same app.** The request id would name
   another call, or none, on that device.

### D. The MCP half

1. **Its own task.** A replay asked by an agent waits for a confirmation
   in the window (ADR-0005); where that pending replay lives (memory or
   store) and how the window shows it is a decision of its own. M8's split
   rule applies: M9.9 becomes the wire, store and window; the MCP tool is
   M9.13, done next (a new id, so the ids other ADRs cite stay).
2. **In this task.** One PR for two decisions.

## Decision

A.1, B.1, C.1, D.1.

## Consequences

- `interaction.proto` gains `ReplayRequest` and `ReplayRefused`,
  `network.proto` `RequestStarted.replay_of`; fixtures
  `replay_started.bin`, `replay_request.bin`, `replay_refused.bin`,
  mirrored in Swift.
- `desktop/app`'s `Replays` keeps the live sessions and, in memory, each
  refusal until its session ends; a new send of the same request clears
  its refusal.
- The button's label warns when the method is not `GET`, `HEAD` or
  `OPTIONS` (ADR-0111: a replayed `POST` runs again on the server).
- Until M9.10 and M9.11, no SDK reads `ReplayRequest`: both drop an
  unknown `interaction` envelope (ADR-0118's evidence), so "Send again"
  says "Sent" and nothing follows.
