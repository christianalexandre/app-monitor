---
id: ADR-0118
title: Breakpoints as their own rule set; a held call named by its request and stage
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0118 — Breakpoints as their own rule set; a held call named by its request and stage

## Context

ADR-0111 decided what a breakpoint does: a rule matched as ADR-0110 B,
holding the request and/or the response on the device, `BreakpointHit`
up, `BreakpointResume` or `BreakpointAbort` down, the call going on
unchanged after a timeout (60 s by default) or when the session ends, and
a resume that carries only the changed fields. It left the wire shape,
the storage and the desktop's side to the task that builds them
(PLAN-0010 M9.7). The window (M9.8) and the SDKs (M9.10, M9.11) build on
what is fixed here.

Already decided, not reopened here:

- Mock rules go as one whole-set `MockRules`, at `HelloAck` and on every
  change, stored per app as a blob, cut past one frame (ADR-0113).
- Breakpoints are made in the window only (ADR-0111 E.1): no pending
  state.
- The device masks what it sends (ADR-0018, ADR-0034).

Evidence, 2026-10-25:

- Wire 7.1.0 generates proto3 `optional` fields as nullable Kotlin
  properties, and swift-protobuf as `has…` accessors
  (`RequestStarted.body_size` already uses it; `make swift-proto` output
  for `BreakpointResume`: `hasBody`, `hasStatusCode`).
- Both SDKs drop an `interaction` envelope of a type they do not read
  (`sdk/core/.../MonitorClient.kt` `receive`,
  `sdk/ios/Sources/AppMonitor/MonitorClient.swift` `read`), so a desktop
  that sends `BreakpointRules` to an SDK of M9.4 or M9.6 changes nothing
  there.
- `RequestStarted` and `ResponseReceived` already carry everything a held
  side has: method, URL, headers, status and a body with its truncation
  (`network.proto`).

## Options

### A. How breakpoint rules reach the device

1. **Their own whole-set message, `BreakpointRules`, pushed as
   `MockRules` is.** Same fields to match (`method`, `host`, `path`),
   plus `request`, `response` and `timeout_ms`. Its own table
   (`breakpoint_rules`), its own match order. A call can then be both
   held and mocked.
2. **A third answer kind in `MockRule`.** One table and one list, but the
   first rule wins, so a breakpoint would shadow a mock, and a rule of
   M9.4's SDKs with an unknown answer reads as one with no answer.

### B. What names a held call

1. **The request id and the stage.** One request is held at most once per
   stage; the hit reuses `RequestStarted` and `ResponseReceived` for what
   it holds, so the window shows it as it shows a capture.
2. **A separate hold id** chosen by the device. One more field to keep
   unique, for no case B.1 misses.

### C. How a resume says "only what changed"

1. **`optional` method, URL, status and body; headers as `set_headers`
   (every header of a listed name replaced by these) and
   `removed_headers` (names, ignoring case).** A header left as `██` is
   simply not listed. An empty `body` is apart from an absent one.
2. **The whole edited side, with `██` meaning "keep".** Breaks a header
   whose real value is `██`, and leaks the masking rule into the protocol.

### D. Where the desktop keeps held calls

1. **In memory, in `desktop/app`'s `HeldCalls`.** A hold lives at most its
   timeout; a restart ends every session anyway. Each hit's envelope is
   still stored raw, as every envelope the desktop does not read is.
2. **In the store.** A table of rows that are all stale after a restart.

### E. The timeout's bounds

1. **1 ms to 10 minutes, checked when a rule is saved.** A breakpoint
   never leaves a call hanging (ADR-0111 B.1); ten minutes is room to edit
   a large body. The desktop drops a held call when the timeout it
   carries passes, from when the hit arrived.
2. **No upper bound.** A forgotten breakpoint could hold an app's call
   for an hour.

## Decision

A.1, B.1, C.1, D.1, E.1. `BreakpointRules` is pushed beside `MockRules`;
the two are independent, in no set order between them.

## Consequences

- The SDKs (M9.10, M9.11) read `BreakpointRules` and keep it as they keep
  `MockRules`, and apply a resume field by field.
- The window (M9.8) edits `HeldCall.unedited` and calls
  `HeldCalls.resume`; the diff is made once, in `resumeOf`.
- A late resume, after the desktop dropped the call, is refused on the
  desktop; one that crosses the device's own timeout is dropped there.
- Every test that reads what a stand-in device gets skips
  `BreakpointRules` (`MockPushTest`, `MocksUiTest`).
