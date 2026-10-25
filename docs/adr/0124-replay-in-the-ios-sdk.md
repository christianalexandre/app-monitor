---
id: ADR-0124
title: How the iOS SDK keeps and sends a task again
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0124 — How the iOS SDK keeps and sends a task again

## Context

ADR-0111 D.1 decided that a replay goes through the app: on
`ReplayRequest(request_id)` the SDK sends that call again "through the
task's session", from the last 200 requests it keeps, refuses an older one
or one of a past session with a reason, and captures the new call with
`replay_of`. ADR-0120 fixed the wire; ADR-0122 A.1 put the kept requests
in the session's `MonitorClient` and said `sdk/ios` follows it. Left open
for `sdk/ios`, inside the agreed architecture (`AGENTS.md`): which task
is made again, from what, how the app's delegate is kept out of it, and
which tasks are refused. Nothing public changes: everything below is
`internal` and debug only (ADR-0048).

PLAN-0010 M9.11 also held the iOS breakpoints. Holding a task needs the
SDK to answer it from a `URLProtocol` that runs the real request itself,
a decision of its own; M8's split rule applies and the breakpoints become
M9.15.

Evidence, 2026-10-25, macOS 26.4 host, scratch binaries
(`build/exp/inval.swift`, `build/exp/iv.swift`, not committed) and then
`ReplayTests`:

- A task made with a completion handler on a session with a delegate:
  the handler got the data; the delegate's `didReceive data` and
  `didCompleteWithError` were not called.
  `aTaskIsSentAgainThroughItsSessionAndCapturedAsAReplay` asserts it.
- A task made on a session after `finishTasksAndInvalidate()` raised
  `NSGenericException` "Task created in a session that has been
  invalidated", which ended the process: Swift cannot catch it.
- `NSURLSession`'s `_invalid_ivar` turned 1 as soon as
  `finishTasksAndInvalidate()` or `invalidateAndCancel()` returned;
  `__NSURLSessionLocal`'s `_isInvalid` only later. A task's `session`
  selector, read by ADR-0046's hooks already, returns its session.
- `uploadTask(withStreamedRequest:)` and a request's `httpBodyStream` hand
  the body as a stream the task reads once (SDD 07: the capture never
  reads it).

## Options

### A. What is sent again, and from what

1. **A new task on the task's own session, from its `originalRequest` and
   the upload body the constructors kept** (SDD 07): a data task, or an
   upload task from the same data or file. The same configuration,
   cookies, `protocolClasses`, credentials and authentication delegate as
   the first time. The session is held weakly: one the app released is
   refused.
2. **A new session from the task's configuration.** Loses the app's
   delegate for authentication challenges and pinning, and leaks a
   session per replay.

### B. How the app's delegate is kept out

1. **A completion handler that drops the result.** The delegate gets no
   data or completion for a task it never made (evidence above); the
   capture's connection hooks still see the response.
2. **A task delegate of the SDK's own** (iOS 15). Also gets the
   authentication challenges the app's delegate answers today.

### C. Which tasks are refused, with what reason

1. **Background sessions** (a completion handler is not allowed there),
   **a streamed body**, **an invalidated session** (read from
   `_invalid_ivar` before the task is made, so the app never crashes; an
   OS without it refuses every replay with its own reason) and **a
   released session**. A download task is sent again as a data task: the
   request is the same, the file is not wanted. A WebSocket is never kept,
   so it is answered as not kept, as on Android.
2. **Catch the exception in an Objective-C shim.** One more language in
   the package for one call, and a task half made by CFNetwork.

## Decision

A.1, B.1, C.1. `RecentRequests` and the `ReplayRequest` reader in
`MonitorClient` mirror `sdk/core` (ADR-0122 A.1); `Hooks.started` keeps
each captured task's sender and sets `replay_of` from the task the sender
made. The iOS end to end asks a replay of the sample's first call
(`--replay /json`, ADR-0122 D.1).

## Consequences

- The kept closures hold each request, upload data included, for at most
  200 tasks per session.
- An app that invalidates a session at the same moment a replay is made on
  it can still raise the exception; DEBT-0084.
- A replay of a redirected task sends its first request again, as
  Android's does (ADR-0122).
