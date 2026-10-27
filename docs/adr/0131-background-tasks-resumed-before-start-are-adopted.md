---
id: ADR-0131
title: Background tasks the resume hook never saw are adopted at their first callback
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0131 — Background tasks the resume hook never saw are adopted at their first callback

## Context

ADR-0046 registers a background task at `resume`, so a task resumed before
`AppMonitor.start`, or in an earlier launch that iOS ended, finished with
no event (DEBT-0039). PLAN-0011 H1.4 asks for it to be captured at
completion, with its start time not invented. Not reopened: the three
session hooks (ADR-0046), masking and body limit (ADR-0036), a background
task is never mocked, held or sent again (ADR-0110 D.1, ADR-0125,
ADR-0124 C.1). Everything below is `internal` and debug only (ADR-0048).

Evidence, 2026-10-25, iOS 26.5 simulator and macOS 26.4 host (Xcode 26.6):

- The `taskMetrics` argument of
  `backgroundTask:didCompleteWithError:taskMetrics:info:reply:` is a
  `__CFN_TaskMetrics`, not a `URLSessionTaskMetrics`; its `info` was empty.
- `NSURLSessionTaskMetrics` has one ivar, `__metrics`, typed
  `@"__CFN_TaskMetrics"`; its `taskInterval` and `transactionMetrics` read
  from it. `__CFN_TaskMetrics` keeps `_createTime` and `_firstResumeTime`.
- `__NSURLBackgroundSession` also holds its tasks in a `_tasks`
  dictionary, but a task carries no start time.
- With option 2, a `/slow` download resumed before `start` reaches the
  sink with its URL, masked headers and body, and a `RequestStarted`
  stamped before the one-second answer (`make swift-sim`).

## Options

1. **Find the task in `_tasks`** — request and response from the task,
   start time unknown. Marking it unknown needs a protocol field, and the
   desktop's storage, UI and MCP to treat a start as optional.
2. **Wrap the daemon's metrics in `URLSessionTaskMetrics`** — set
   `__metrics` on an empty `URLSessionTaskMetrics`, then read the public
   `transactionMetrics` (request, response) and `taskInterval.start`, the
   start `nsurlsessiond` recorded. One private ivar name, checked against
   the argument's class; no protocol change.

## Decision

Option 2. The first of the three session callbacks for an identifier the
table lacks, while the monitor runs, adopts the task with a new
`TaskCapture`. At completion, `RequestStarted` is sent from the first
transaction's request, stamped `taskInterval.start`, then the response
as for any background task; its replay answers with ADR-0124's refusal.
A request that is not `http(s)` or carries `sdk/ktor`'s capture header is
dropped, as at `resume`. A body the metrics do not hold, on a method
other than `GET` or `HEAD`, is marked truncated. Without the ivar, or
without metrics, an adopted task sends nothing, as before.

The start time is the real one, not an unknown one: H1.4's "marked as
unknown rather than invented" is met by not inventing it.

## Consequences

- DEBT-0039 is paid for tasks resumed before `start`; a task from an
  earlier launch takes the same path, but no test relaunches the app.
- One more private name, `__metrics`, beside ADR-0046's; a missing or
  retyped ivar turns adoption off without touching the app's callbacks.
- An adopted upload's request body is never seen.
