---
id: ADR-0190
title: An iOS WebSocket's end is read where the task finishes
status: Accepted
date: 2026-11-04
superseded_by:
---

# ADR-0190 — An iOS WebSocket's end is read where the task finishes

## Context

DEBT-0038: ADR-0045 hooks only the WebSocket task's own methods, and none
of them runs when a socket drops after the `101`, when the app calls a bare
`cancel()`, or when the peer closes with no `receive` pending. Already
decided, not reopened here: ADR-0045's hooks for the upgrade and the
frames, ADR-0043's hooks mechanism, ADR-0187's `MethodHook`, and a socket
that drops after the upgrade is a `RequestFailed`, as in OkHttp's capture
(SDD 05).

Evidence, checked on 2026-11-04 on macOS 26.4 with a throwaway probe that
logged the task's methods against a Network.framework echo server:

- `__NSURLSessionWebSocketTask` inherits `_onqueue_didFinishWithError:`
  from `__NSCFLocalSessionTask`. It ran once in every case probed: refused
  connection (`NSURLErrorDomain -1004`), rejected upgrade (`-1011`, after
  its response), `cancel()` before and after the upgrade (`-999`),
  `cancel(with:reason:)` (nil error, `closeCode` 1000), the peer's close
  with a `receive` pending (nil error, `closeCode` 4000, before the
  `receive` handler) and a peer that drops with a `receive` pending
  (`NSPOSIXErrorDomain 54`). `closeCode` and `closeReason` are set before
  it runs.
- A bare `cancel()` after the upgrade sends a close frame without a status
  code: the server read a close with no payload (`noStatusReceived`).
- With no `receive` pending, the peer's close or drop changed nothing on
  the task after one second: `state` stayed running, `closeCode` invalid,
  no method ran. CFNetwork reads the socket only for a `receive`.
- `connection:didFinishLoadingWithError:`, ADR-0045's failure hook, runs
  for a refused connection only.

## Options

1. **Hook `_onqueue_didFinishWithError:` on `__NSCFLocalSessionTask`** —
   one point for every end; HTTP tasks pass through it too and find no
   WebSocket capture. One more private name, on the superclass.
2. **Observe the task's `state` with KVO** — public, but whether
   CFNetwork's `setState:` notifies observers was not probed, and one
   observation per task must be removed on time.
3. **Hook the framing below the task** (ADR-0045 option 3) — would see a
   peer's close with no `receive` pending, but the task itself has not
   read it then, and it is another private layer.

## Decision

Option 1, in place of ADR-0045's `connection:didFinishLoadingWithError:`
hook and of the close read in the `receive` handler. At the finish: no
response yet and an error → `RequestFailed`; after a `101`, a close code →
a received close frame unless the app's close was recorded first, a cancel
→ a sent close frame with an empty payload, any other error →
`RequestFailed`; after another status, nothing (the response was the end).

## Consequences

- Every end the task sees reaches the desktop: a drop after the upgrade
  fails as on Android, a bare `cancel()` is its close frame, a cancel
  before the upgrade fails as cancelled.
- A peer's close or drop with no `receive` pending is recorded when the
  app next receives or cancels, as the app itself learns it; that is not
  debt, the task has not read it.
- A bare `cancel()` on a socket the peer already dropped is recorded as a
  sent close that never left. Pings and pongs are still not seen
  (DEBT-0029).
- If `_onqueue_didFinishWithError:` is missing, WebSocket capture is off
  with ADR-0045's log line.
