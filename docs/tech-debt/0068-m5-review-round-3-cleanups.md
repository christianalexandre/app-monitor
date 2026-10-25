---
id: DEBT-0068
title: Non-correctness findings of the third M5 milestone review
status: Open
date: 2026-10-24
trigger: The first PR that touches `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Ingest.kt`, `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Storage.kt`, `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/WriteQueue.kt` or `desktop/server/src/main/kotlin/appmonitor/desktop/server/AppMonitorServer.kt` after M5.R10 is `[x]` in PLAN-0006
paid_date:
---

# DEBT-0068 — Non-correctness findings of the third M5 milestone review

## What is missing

Findings of the `code-review` of `66c4154..81fed8e` that are not wrong
results, crashes, hangs, leaks, races or privacy issues (ADR-0016):

- **A failed retry leaves no error.** `Ingest.retryLost` catches its
  exception and returns 0, so a disconnect that never writes is counted
  in `notSaved` with no `lastError` once the next batch is written, and
  nothing logs why.
- **A retry clears another batch's error.** `WriteQueue.writeBatch` resets
  health to `StorageHealth(notSaved - recovered)` after a written retry,
  dropping a `lastError` that came from a different lost batch.
  `StorageHealth`'s documented rule ("cleared by the next batch or retry
  written") allows it, but the cause is hidden one batch early.
- **`close`'s lost count leaves out pending lost disconnects.** A
  disconnect still lost when `close` ends is not in the returned count
  nor in the "items not saved" line; the next open ends that session.
- **`whileOpen` swallows cancellation.** It reads any
  `CancellationException` with an active caller as "closed", and relies on
  kotlinx's handling of a rejected dispatch; a cancellation raised inside
  the block for another reason would be returned as a stale status.
- **Rollback copied.** `retryLost` repeats `write`'s snapshot and restore
  of rows and connections (see DEBT-0067's "rollback rebuilt by hand").
- **`published` is a `Boolean?`.** In `AppMonitorServer`'s handshake, null
  means "forgotten"; a later removal from `connections` for another reason
  would answer the device 1008 "Forgotten". A sealed result would name
  each outcome.
- **One closing guard per method.** Every new `Storage` call that touches
  the driver must remember `whileOpen` (or M5.R10's read guard); one
  close-aware guard on the driver would hold for all of them.

## Why it was accepted

No wrong data: every finding changes a status message, a log line, or
how hard the next change is to get right. Stored rows are the same, and
M5.R9 and M5.R10 fix the two races that do change them.

## Trigger

The first PR that touches
`desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Ingest.kt`,
`desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Storage.kt`,
`desktop/storage/src/main/kotlin/appmonitor/desktop/storage/WriteQueue.kt`
or `desktop/server/src/main/kotlin/appmonitor/desktop/server/AppMonitorServer.kt`
after M5.R10 is `[x]` in PLAN-0006. M5.R9 and M5.R10 rewrite `retryLost`
and the closing guard, so paying these first would be undone.

## How it was paid

> Filled when closing.
