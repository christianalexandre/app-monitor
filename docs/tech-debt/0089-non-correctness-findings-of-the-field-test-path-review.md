---
id: DEBT-0089
title: Non-correctness findings of the field-test path review
status: Paid
date: 2026-10-25
trigger: "The next PR that touches `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/MonitorHolder.kt` or `sdk/ios/Sources/AppMonitor/MonitorClient.swift`"
paid_date: 2026-10-30
---

# DEBT-0089 — Non-correctness findings of the field-test path review

## What is missing

Findings of PLAN-0011's H1.1 review that normal use does not hit
(ADR-0094):

- ~~**A client that stopped itself stays held.**~~ Gone with ADR-0156:
  no cleartext check is left to stop the client, and nothing else makes it
  call its own `stop()`.
- **The Swift client logs one connect failure per life.** Paid, see
  below. In
  `MonitorClient.swift`, `connectFailureLogged` is never reset after a
  `HelloAck`. The Kotlin client resets it there. A desktop that moves
  after a first good session fails without a log line.
- **The OkHttp request body is captured up to three times.** Paid, see
  below.
  `AppMonitorInterceptor` calls `request.body.capture(limit)` for
  `RequestStarted`, for a request breakpoint and for a response
  breakpoint's hit, though the first result is at hand.

## Why it was accepted

None of them shows wrong data or loses any. Fixing the network policy
needs a rebuild, which starts a new client. The missing log is a
diagnostic. The repeated writes cost time on a body that can be read
again. PLAN-0011 keeps its review tasks to what the field test hits.

## Trigger

The next PR that touches
`sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/MonitorHolder.kt` or
`sdk/ios/Sources/AppMonitor/MonitorClient.swift` pays the item for that
file.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.

- **OkHttp request body** (PLAN-0011 H1.2, which touched
  `AppMonitorInterceptor.kt`): the body is captured once, for the request
  breakpoint's hit when it is not edited, else after it, and that capture
  serves `RequestStarted` and the response breakpoint's hit.
  `BreakpointTest.aBodyHeldAtBothStagesIsCapturedOnce` counts the writes.
- **Swift connect failure log** (the PR whose hit goes on when never
  written, which touched `MonitorClient.swift`): `connectFailureLogged`
  is reset at `HelloAck`, as in Kotlin.
  `MonitorClientTests.aConnectFailureAfterAGoodSessionIsLoggedAgain`
  fails without it. Only the `MonitorHolder.kt` trigger is left, and no
  item of it is open.
