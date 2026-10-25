---
id: DEBT-0089
title: Non-correctness findings of the field-test path review
status: Open
date: 2026-10-25
trigger: "The next PR that touches `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/MonitorHolder.kt`, `sdk/ios/Sources/AppMonitor/MonitorClient.swift` or `sdk/android-okhttp/src/commonMain/kotlin/appmonitor/sdk/okhttp/AppMonitorInterceptor.kt`"
paid_date:
---

# DEBT-0089 — Non-correctness findings of the field-test path review

## What is missing

Findings of PLAN-0011's H1.1 review that normal use does not hit
(ADR-0094):

- **A client that stopped itself stays held.** When cleartext is
  blocked, `MonitorClient` calls its own `stop()`, but `MonitorHolder`
  keeps it. A later `AppMonitor.start` is ignored, and every capture
  module keeps building envelopes that `send` then drops.
- **The Swift client logs one connect failure per life.** In
  `MonitorClient.swift`, `connectFailureLogged` is never reset after a
  `HelloAck`. The Kotlin client resets it there. A desktop that moves
  after a first good session fails without a log line.
- **The OkHttp request body is captured up to three times.**
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
`sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/MonitorHolder.kt`,
`sdk/ios/Sources/AppMonitor/MonitorClient.swift` or
`sdk/android-okhttp/src/commonMain/kotlin/appmonitor/sdk/okhttp/AppMonitorInterceptor.kt`
pays the item for that file.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
