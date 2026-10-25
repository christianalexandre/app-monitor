---
id: DEBT-0073
title: Non-correctness findings of the third M6 milestone review
status: Open
date: 2026-10-25
trigger: "The first PR that touches `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/McpController.kt` or `desktop/app/src/test/kotlin/appmonitor/desktop/app/McpWiringTest.kt`, or a `setEnabled` called off the UI dispatcher"
paid_date:
---

# DEBT-0073 — Non-correctness findings of the third M6 milestone review

## What is missing

Findings of the `code-review` of `0a36f7a..6f9f01d` that are not wrong
results, crashes, hangs, leaks, races or privacy issues today (ADR-0016):

- **Two `setEnabled` calls rely on call order.** Each sets `enabled`, then
  writes the setting, then takes `lock` with its own `on`. On the app's
  single UI dispatcher both calls go through the write queue's fair
  `admission` mutex and then the fair `lock` in call order, so the last
  call wins. Called from several threads, an earlier `false` could take
  `lock` last and stop the server while the switch shows on. Taking
  `lock` with the current `enabled` instead of `on` removes the order
  dependency.
- **The race test guards in some runs only.** `McpWiringTest`'s
  `switchTurnedOffAsStartWritesItEndsOff` races two threads 2,000 rounds;
  it caught the old code in 2 of 6 runs (PLAN-0007 M6.R5), so a
  regression can pass CI.
- **`to_curl` keeps only a literal `0`.** A stored `Content-Length: 00`
  is dropped, and two stored `Content-Length: 0` lines are both sent.
- **The framing filter mixes rules.** `keepsEmptyLength` adds a value, a
  `HEAD` and a body-size check on top of the name filter; one
  `sendsBody` flag would put M6.R6's rule in one place.
- **The tool description is unclear.** "a Content-Length: 0 is kept when
  there is none" does not say none of what (a body).

## Why it was accepted

None changes what the app or a tool answers today: `setEnabled` is only
called from the window's `rememberCoroutineScope`, the race test passes
on the fixed code, and a zero length written as `00` or twice is not one
any SDK in this repository stores.

## Trigger

The first PR after PLAN-0007 closes that touches
`desktop/app/src/main/kotlin/appmonitor/desktop/app/store/McpController.kt`,
`desktop/app/src/test/kotlin/appmonitor/desktop/app/McpWiringTest.kt` or
`desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/ToCurl.kt`, or a
`setEnabled` called off the UI dispatcher.

Fired by PLAN-0008 M7.6 (`feat/har-curl-export-in-window`), which splits
`ToCurl.command` out for the window. It paid the three `to_curl` parts:
one `sent` filter keeps the first zero `Content-Length`, however written
(`ToCurlTest.oneZeroLengthIsKeptHoweverWritten`), under one
`keepZeroLength` flag, and the description says "when no body is sent".
Left: the two `McpController` parts, in files this PR does not change;
the trigger is narrowed to them.

## How it was paid

> Filled when closing.
