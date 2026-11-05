---
id: DEBT-0110
title: A stalled desktop breakpoint test timed out once on CI
status: Paid
date: 2026-10-30
trigger: A second failure of `StalledDesktopBreakpointTest` in `sdk/core/src/jvmTest/kotlin/appmonitor/sdk/core/StalledDesktopBreakpointTest.kt`, or PLAN-0015 H5.2 starts
paid_date: 2026-10-31
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

Paid by PLAN-0015 H5.2c, at the test's cause. It waited for the first
drop and took that as a stalled pipe, but one drop proves only a burst
the writer had not kept up with. When the writer caught up, it took the
hit from the buffer and wrote it to a desktop that never answers, so
`hold` waited the hit's 60 s and the test's 5 s bound fired.

Evidence: on the owner's MacBook (10 cores) with ten `yes` processes
running, the old test failed 3 of 18 runs, and 1 of 13 with the drop
count printed: in the failed one it stayed at 1 after the hit and two more
frames, so none was dropped; it went from 1 to 4 in each green run.

The test now fills the buffer, pauses 200 ms, sends three more frames,
and goes on only when all three were dropped: the writer took nothing in
that pause. Under the same load: 50 runs of 50 green, each 3.2 to 3.7 s
(about 1.4 s before).
