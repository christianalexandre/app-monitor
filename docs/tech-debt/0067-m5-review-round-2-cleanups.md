---
id: DEBT-0067
title: Non-correctness findings of the second M5 milestone review
status: Open
date: 2026-10-24
trigger: The first PR that touches `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Ingest.kt`, `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Storage.kt` or `desktop/storage/src/test/kotlin/appmonitor/desktop/storage/StorageOpenTest.kt` after M5.R10 is `[x]` in PLAN-0006
paid_date:
---

# DEBT-0067 — Non-correctness findings of the second M5 milestone review

## What is missing

Findings of the `code-review` of `055c62b..66c4154` that are not wrong
results, crashes, hangs, leaks, races or privacy issues (ADR-0016):

- **Rollback rebuilt by hand.** `Ingest.write`'s catch restores rows, run,
  retention and connections one case at a time, and `lostDisconnects`
  re-applies items. Every new item that changes `connections` needs its
  own branch. Applying a batch to a working copy that replaces the state
  only once the transaction returns would make rollback "drop the copy".
- **Allocations on the write path.** Each batch copies `connections` with
  `toMap()` for a rare rollback, and the catch rescans the batch for each
  lost `Disconnected`'s `Connected`; one map of the batch's `Connected`
  items would do it in one pass.
- **Cleanup on every retention pass.** `Storage.applyRetention` now runs
  the unused device and app deletion and `releaseFreePages` on every
  pass, the idle 10-minute ones included. A flag set by a superseded pass
  would limit it to the passes that need it.
- **`StorageOpenTest.opensInADirectoryItCannotChmod` shares `/tmp`.** It
  writes `appmonitor.db` into the system `/tmp` and returns without
  asserting when the files are already there, so two runs at once (loop
  worktree and main checkout) or a crashed run's leftovers skip or race it.

## Why it was accepted

No wrong result: the rollback paths are covered by `IngestTest`'s lost
batch cases, the copies and the extra pass cost writer time that stays
inside `TrafficStoreTest`'s and `RetentionTest`'s bounds, and the `/tmp`
test skips rather than passes falsely on a single run.

## Trigger

The first PR that touches
`desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Ingest.kt`,
`desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Storage.kt`
or `desktop/storage/src/test/kotlin/appmonitor/desktop/storage/StorageOpenTest.kt`
after M5.R10 is `[x]` in PLAN-0006. M5.R6 and M5.R7 rewrite the same
rollback and close code, so paying these first would be undone.
Re-triggered by the third milestone review: M5.R9 and M5.R10 rewrite
`retryLost` and the store's closing guard.

## How it was paid

> Filled when closing.
