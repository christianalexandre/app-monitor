---
id: ADR-0130
title: A held iOS task asks the app's delegate
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0130 — A held iOS task asks the app's delegate

## Context

ADR-0125 B.1 sends a held task on through a session of `HoldProtocol`'s
own; its authentication challenges got default handling and its
redirects were followed, so the app's delegate was never asked
(DEBT-0085). A task that works without a breakpoint could fail TLS, or
follow a redirect the app refuses, once a breakpoint matched it. Not
reopened: who sends the task on (ADR-0125 B.1), which tasks are held
(C.1). Everything below is `internal` and debug only (ADR-0048).

Evidence, 2026-10-25, macOS 26.4 host, `URLSessionCaptureTests`:

- Before this change, a held task on a session whose delegate cancels a
  Basic challenge on `/auth` got the 401 and no challenge; a delegate that
  refuses the redirect of `/redirect` saw none, and the server got
  `/redirect` then `/ok`.
- With it, the four tests below pass; they fail without it (12 issues).

## Options

### A. How a challenge reaches the app

1. **The protocol's session delegate asks the app's delegates itself**,
   on the app's session `delegateQueue`, with the app's session and the
   held task, in URLSession's order: the task's own delegate
   (`URLSessionTask.delegate`), then for a session-wide method (server
   trust, client certificate, NTLM, Negotiate) the session delegate's
   `urlSession(_:didReceive:completionHandler:)`, then its task-level
   method; none implemented, default handling. The app's completion
   handler answers the inner task directly.
2. **`client.urlProtocol(_:didReceive:)`** with a challenge whose sender
   is the protocol, mapped back to the inner handler. URLSession decides
   who is asked, but how it bridges a protocol's challenge to a task
   delegate is undocumented.

### B. How a redirect reaches the app

1. **Asked the same way**: the task's delegate, then the session's;
   neither implements it, followed. Refused, the inner session ends on
   the `3xx`, which the task gets as its response; followed, the inner
   session goes on to the new URL.
2. **`client.urlProtocol(_:wasRedirectedTo:redirectResponse:)`**. The
   load restarts with a new protocol instance, which `canInit` would hold
   again, and the request read at resume is the first one.

## Decision

A.1, B.1: the same path for both, deterministic and tested on the host.

## Consequences

- A held task's challenges and redirects are the app's delegate's, as
  without the breakpoint; DEBT-0085 is paid.
- The app's delegate is called by the SDK, not by URLSession: the session
  argument is the app's, the challenge's sender is the inner session's.
- A session-wide challenge is not tested: the Swift tests have no TLS
  origin. The path taken is the task-level one, proven with Basic.
