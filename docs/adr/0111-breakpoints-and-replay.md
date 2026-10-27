---
id: ADR-0111
title: Breakpoints hold a call on the device; replay goes through the app
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0111 — Breakpoints hold a call on the device; replay goes through the app

## Context

M9 also brings breakpoints (hold a call until the developer lets it go,
edited or not) and replay (send a stored exchange again) (PLAN-0010).
Both act on the app's calls from the desktop: security, a "stop and ask"
area (`AGENTS.md`). Replay also decides what is sent on the developer's
behalf, and with which credentials.

Already decided, not reopened here:

- Rules, their matcher and the `interaction` channel are ADR-0110's.
  Breakpoints reuse its matcher (B) and its channel.
- The device masks before it sends: `Authorization`, `Cookie` and the
  other masked values reach the desktop as `██`, unless `masking = false`
  (ADR-0018, ADR-0034, ADR-0036). The desktop stores what it got
  (ADR-0081).
- MCP tools that change app behaviour need a confirmation in the UI
  (ADR-0005).

Evidence, 2026-10-25:

- OkHttp 5.3.2: `Interceptor.Chain.call()` returns the `Call`, and
  `Call.clone()` makes a new, identical call that has not run yet
  (`javap` on `okhttp-jvm-5.3.2.jar`).
- Ktor: the plugin is installed on an `HttpClient`, so it holds the client
  that a new request can go through (SDD 08).
- `URLSession`: the capture already keeps state on each task (SDD 07: the
  request id is an associated object). The session can make a new task
  from the original `URLRequest`.
- What a held call does to the app's own timeouts is not measured yet.
  `URLRequest.timeoutInterval` defaults to 60 s, and OkHttp's call timeout
  defaults to none. M9.5 tests both.

## Options

### A. What a breakpoint holds, and where

1. **The request before it is sent and/or the response before the app
   reads it, held on the device.** A breakpoint is a rule (ADR-0110 B)
   with "request", "response" or both. On a hit the SDK sends
   `BreakpointHit` with what it holds, then waits for `BreakpointResume`
   (continue, edited or not) or `BreakpointAbort` (fail the call like an
   ADR-0110 C failure). Only the waiting call waits; the rest of the app
   goes on.
2. **Requests only.** This is simpler, but "what does the screen do with
   this response?" is the most common case.

Recommended: 1.

### B. When the desktop does not answer

1. **The call continues unchanged after a timeout (60 s by default, set
   per breakpoint), or at once when the session ends.** A breakpoint never
   leaves a call hanging, and it never fails a call the developer did not
   fail.
2. **The call fails** after the timeout or on disconnect.

Recommended: 1.

### C. Masked values on resume

1. **The resume carries only the fields the developer changed.** A field
   left alone keeps the device's own value, so an `Authorization` shown as
   `██` still goes out with the real token. Setting a masked header sends
   the new value.
2. **The resume carries the whole edited request.** Every masked header
   would then go out as the literal `██`.

Recommended: 1.

### D. Where a replay is sent from

1. **Through the app, on the device.** The desktop sends
   `ReplayRequest(request_id)`. The SDK sends that call again through the
   app's own client: `Call.clone()`, the plugin's `HttpClient`, or the
   task's session. That means the same cookies, interceptors, real
   credentials and TLS settings as the first time. The SDK keeps the last
   200 requests in memory for this. An older one, or one from a past
   session, is refused with a reason. The new exchange is captured as
   usual, with `replay_of` set to the original's request id.
2. **From the desktop's own HTTP client.** This works for any stored
   exchange, even from a past session. But every masked value goes out as
   `██`, so an authenticated call fails unless masking is off, and none of
   the app's interceptors run.

Recommended: 1.

### E. Breakpoints, replay and MCP

1. **Breakpoints from the window only. Replay from the window, and from
   MCP under ADR-0005's confirmation.** An agent cannot hold a call: it
   cannot see the app's screen while the call waits. A replay is one
   bounded action, which the owner can confirm.
2. **Both from MCP under confirmation.**

Recommended: 1.

## Decision

A.1, B.1, C.1, D.1, E.1 (every recommended option).

Accepted by the agent on the owner's standing instruction of 2026-10-25
(take the recommended options while the owner is away).

## Consequences

- new `interaction` messages (`BreakpointHit`,
  `BreakpointResume`, `BreakpointAbort`, `ReplayRequest`) and a
  `replay_of` field in `RequestStarted`. Each SDK keeps a bounded
  in-memory map of recent requests.
- A held response delays the app's read of it, and the app's own timeouts
  may fire first. M9.5's tests show which, per client.
- A replayed `POST` really runs a second time on the server. The window
  says so on the button.
