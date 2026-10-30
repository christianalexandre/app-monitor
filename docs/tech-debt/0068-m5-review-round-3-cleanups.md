---
id: DEBT-0068
title: Non-correctness findings of the third M5 milestone review
status: Open
date: 2026-10-24
trigger: "The first PR that changes `Ingest.retryLost`, `Storage.close` or `Storage.whileOpen`, or touches `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/WriteQueue.kt` or `desktop/server/src/main/kotlin/appmonitor/desktop/server/AppMonitorServer.kt`"
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
- **`published` is a `Boolean?`.** (Paid by PLAN-0010 M9.2: the
  handshake names each outcome with a `Publication` enum.)
- **One closing guard per method.** Every new `Storage` call that touches
  the driver must remember `whileOpen` (or M5.R10's read guard); one
  close-aware guard on the driver would hold for all of them.

## Why it was accepted

No wrong data: every finding changes a status message, a log line, or
how hard the next change is to get right. Stored rows are the same, and
M5.R9 and M5.R10 fix the two races that do change them.

## Trigger

The first PR that changes `Ingest.retryLost`, `Storage.close` or `Storage.whileOpen`, or touches `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/WriteQueue.kt` or `desktop/server/src/main/kotlin/appmonitor/desktop/server/AppMonitorServer.kt`.

Fired by PLAN-0007 M6.3, whose `Storage` change is read-only (its new
reads go through the existing read guard) and whose `Ingest` change is one
queued settings write: `retryLost`, health, `close`, `whileOpen` and the
handshake are untouched. Narrowed to that code.

Fired by PLAN-0008 M7.2, whose `Ingest` change adds two queued issue
items: `retryLost`, health, `close`, `whileOpen` and the handshake are
untouched. Re-triggered past PLAN-0008, whose milestone review reads this
code.

Fired by PLAN-0009 M8.4, whose `Ingest` change is one line in `network`
(ADR-0104): `retryLost`, health, `close`, `whileOpen` and the handshake
are untouched. Narrowed to that code.

Fired by PLAN-0010 M9.2 (`feat/interaction-channel`), which adds the
outgoing queue after `HelloAck` in `AppMonitorServer`'s handshake: it
paid the `published` item there (a `Publication` enum). `retryLost`,
health, `close` and `whileOpen` are untouched.

Fired by the PR that closes the write queue when its writer ends with an
`Error` (#313, an external review's T5), which changes `writeBatch`'s catch. "A retry clears another
batch's error" is not paid. `aLostDisconnectRetriedByALoneFlushClearsTheError`
pins that a retry written alone clears the error, and keeping another
batch's error needs to know which lost item each error came from, which
the health does not keep. Kept as is.

## How it was paid

> Filled when closing.
