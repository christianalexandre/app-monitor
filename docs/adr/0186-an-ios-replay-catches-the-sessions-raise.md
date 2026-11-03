---
id: ADR-0186
title: An iOS replay catches the session's raise in Objective-C
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0186 — An iOS replay catches the session's raise in Objective-C

## Context

ADR-0124 C.1 refuses a replay on an invalidated session by reading
`NSURLSession`'s `_invalid_ivar` before the task is made, and left a race:
an app invalidating the session on another thread between the read and the
task raises `NSGenericException`, which ends the debug app (DEBT-0084).
ADR-0124 C.2 rejected catching it for "one more language in the package"
and "a task half made by CFNetwork". Since ADR-0100 the package has a C
target, so an Objective-C file adds no target; nothing public changes.

Evidence, 2026-11-03, macOS 26.4 host:

- A scratch binary (`build/exp/inv.m`, not committed): on a session with no
  task yet, `dataTaskWithRequest:`, `dataTaskWithRequest:completionHandler:`
  and `uploadTaskWithRequest:fromData:completionHandler:` raised right after
  `finishTasksAndInvalidate` and `invalidateAndCancel`, and 0.5 s later;
  `@catch` caught each one, and the process went on.
- `ReplayTests.aTaskOfAnInvalidatedSessionIsRefused` with only the catch: on a
  session that has run a task, a task made right after
  `finishTasksAndInvalidate` raised nothing, and came back made. So the catch
  alone does not refuse every invalidated session, and the ivar stays.
- An exception unwinding Swift frames was not tried. The catch sits in the
  frame that calls the session, so none is unwound and the question never arises.

## Options

1. **Catch in Objective-C, around the three constructors, and keep reading the
   ivar first.** A few lines beside the C target. An OS without the ivar no
   longer refuses every replay: the catch still keeps the app alive.
2. **Hook both invalidation methods and share a lock with the app's call**
   (DEBT-0084). Two more private hooks on the session's class cluster, and the
   app's own call waits on the SDK's lock.
3. **Catch only, without the ivar.** One private name fewer, and a replay on a
   session invalidated a moment ago goes on as a made task, against the second
   piece of evidence.

## Decision

Option 1. `appmonitor_replay_task` (`AppMonitorConnections/replay_task.m`) makes
the task inside `@try`; nil is refused like the ivar's answer, "The app's session
was invalidated". This changes ADR-0124's C.2 and the sentence of its C.1 that an
OS without the ivar refuses every replay; the rest of ADR-0124 stands.

## Consequences

- DEBT-0084 is paid: `aReplayRacingTheAppInvalidatingItsSessionEndsTheAppNever`
  runs the race 100 times, on the macOS host and on the simulator.
- `AppMonitorConnections` holds one thing that is not about connections: the
  package's only Objective-C, kept there rather than in a target of its own that
  both manifests and `swift_api_check.sh` would have to name.
- A raise for another reason reads as an invalidated session. None is known.
