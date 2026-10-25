---
id: ADR-0125
title: How the iOS SDK holds a task at a breakpoint
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0125 — How the iOS SDK holds a task at a breakpoint

## Context

ADR-0111 decided what a breakpoint does on the device, ADR-0118 fixed the
wire, and ADR-0123 settled the Kotlin side: the state in the session's
client, a request hold before the mock lookup, `RequestStarted` sent once
with what went out, an edit the client refuses failing the call.
ADR-0124 split the iOS breakpoints off as PLAN-0010 M9.15, because
holding a task needs the SDK to answer it from a `URLProtocol` that runs
the real request itself. Left open for `sdk/ios`, inside the agreed
architecture (`AGENTS.md`): how a task is held without blocking the
thread that resumed it, who sends it on, and which tasks are held.
Nothing public changes: everything below is `internal` and debug only
(ADR-0048).

Already decided, not reopened here: the getter of ADR-0117 A.1 puts the
SDK's protocols first in every local configuration, and the connection
hooks of ADR-0043 see what a protocol hands the task (ADR-0117 C.1).

Evidence, 2026-10-25, macOS 26.4 host, `BreakpointTests`:

- `resume()` runs on the app's thread, the main one included, so the
  `RequestStarted` hook cannot wait there. A `URLProtocol` runs on
  CFNetwork's loading thread, whose run loop already fires the mock's
  delay `Timer` (ADR-0117 D.1).
- By `startLoading`, a `POST`'s `httpBody` is gone from both the
  protocol's `request` and the task's `currentRequest`: the first run of
  `aHeldRequestGoesOutEditedAndIsCapturedAsSent` held an empty body. The
  request read at resume has it.
- A task on a session with a delegate, held at both stages and let go at
  the timeout: the delegate got the body and `didCompleteWithError`
  (`aHeldTaskGoesOnUnchangedAtItsTimeoutAndItsDelegateGetsTheData`).
- A held task the app cancels: `stopLoading` runs before the connection's
  `_didFinishWithError:`, so a `RequestStarted` sent there comes before
  the capture's `RequestFailed`
  (`aHeldTaskTheAppCancelsIsCapturedStartedThenFailed`).

## Options

### A. How a task waits

1. **`HoldProtocol`, a `URLProtocol` put before `MockProtocol`**, loads
   every task a breakpoint matched at its first resume. It sends the hit
   and waits on the loading thread's run loop; the resume, the abort, the
   timeout or the session's end runs there as a block. `stopLoading`
   drops the hold.
2. **Suspend the task in the resume hook** and resume it later. The app
   reads `state` as running, and nothing can edit a request CFNetwork has
   already taken.

### B. Who sends a held task on

1. **A new session from a copy of the task's session configuration**,
   its task marked so it is neither captured nor held, then
   `finishTasksAndInvalidate()`. The same cookies, cache, headers and
   timeouts as the app's; redirects followed, challenges answered by
   default handling.
2. **Forward the protocol's own load** to CFNetwork's HTTP protocol. No
   public API does it.

### C. Which tasks are held

1. **What ADR-0117 B.1 mocks** (a data or upload task of a local session),
   **without a streamed body**, which only the app's delegate can hand
   again. Both rules are read at resume: a request rule defers
   `RequestStarted` to the protocol, which sends it once with the request
   as resumed and the mock it then matched; a response rule is matched
   again on the request as sent.
2. **Hold background tasks too.** Their transfer runs in `nsurlsessiond`,
   out of every protocol's reach (ADR-0046).

## Decision

A.1, B.1, C.1. An abort fails the task as a mock failure does
(`NSURLErrorNetworkConnectionLost`, the message as its description); an
edit to a URL that is not `http` or `https` with a host, or to an empty
method, fails it with `NSURLErrorBadURL` "Breakpoint edit refused: …",
after the held `RequestStarted` (ADR-0123 D.1). A held response is read
whole first, then handed over unchanged or edited; a replaced body is not
matched to `Content-Length`, as on Android.

## Consequences

- An iOS app's data and upload tasks are held as Android's calls are; a
  desktop of M9.8 shows them (SDD 12).
- A held task's authentication challenges and redirects are not the
  app's delegate's to answer; DEBT-0085.
- A held response is in memory whole until it is let go.
- The hold is proven on the macOS host; the simulator's hosted tests do
  not run it; DEBT-0086.
