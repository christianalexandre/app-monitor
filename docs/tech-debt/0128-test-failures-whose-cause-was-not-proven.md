---
id: DEBT-0128
title: Test failures whose cause was not proven
status: Open
date: 2026-11-05
trigger: One of `BreakpointsUiTest.kt`, `LifecycleTest.kt`, `MonitorClientTests.swift` or `TestDesktop.swift` fails again in the way this item describes
paid_date:
---

# DEBT-0128 — Test failures whose cause was not proven

## What is missing

Four failures seen around release PR #379, none explained by a reproduction:

- `BreakpointsUiTest`, *a breakpoint moves up and down*: timed out once on the
  gate's Windows runner waiting for the second move (run `42045925738`, attempt 2).
  The test now waits for the list before that click, as `MocksUiTest` does, but
  150 moves on macOS, idle and under load, never found the button disabled.
- `LifecycleTest.callInFlightCompletesDuringStop`: fails under 200 busy loops on
  a 10-core Mac (3 of 3), not under 60 (0 of 4), never in CI. The call has 400 ms
  left of `McpServer.GRACE` after the test's `delay(100)`.
- `aConnectFailureAfterAGoodSessionIsLoggedAgain`: took 41 s on bounds of 5 s
  (run `41866461707`).
- `TestDesktop`'s port: which socket held 22755 (run `41889263190`) is not known.
  `TestDesktop` now draws another.

## Why it was accepted

Each may be a slow runner and nothing more, and each has passed since; there is
no failing run left to study. Changing `McpServer`'s timing for a test that fails
only at that load would change what the app does for no observed failure.

## Trigger

One of `BreakpointsUiTest.kt`, `LifecycleTest.kt`, `MonitorClientTests.swift` or
`TestDesktop.swift` fails again in the way this item describes: keep the run's
logs, and find the cause before rerunning it.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
