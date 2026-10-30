---
id: DEBT-0110
title: A stalled desktop breakpoint test timed out once on CI
status: Open
date: 2026-10-30
trigger: A second failure of `StalledDesktopBreakpointTest` in `sdk/core/src/jvmTest/kotlin/appmonitor/sdk/core/StalledDesktopBreakpointTest.kt`, or PLAN-0015 H5.2 starts
paid_date:
---

# DEBT-0110 — A stalled desktop breakpoint test timed out once on CI

## What is missing

`StalledDesktopBreakpointTest[jvm] > aHitDroppedFromAStalledBufferGoesOnUnchanged()`
failed with `kotlinx.coroutines.TimeoutCancellationException` in the
`check` job of PR #317 (run 41107618581, attempt 1, ubuntu), failing
`:sdk:core:allTests`. #317 does not touch `sdk/core`'s breakpoints, and
attempt 2 passed. The cause is not known.

## Why it was accepted

One failure, no reproduction yet; the loop retried the job and recorded
nothing. PLAN-0015 H5.2 reproduces each CI-only failure and fixes it at
its cause.

## Trigger

A second failure of `StalledDesktopBreakpointTest`, or PLAN-0015 H5.2
starts.

## How it was paid

> Filled when closing.
