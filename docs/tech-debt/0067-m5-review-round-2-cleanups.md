---
id: DEBT-0067
title: Non-correctness findings of the second M5 milestone review
status: Open
date: 2026-10-24
trigger: "The first PR that changes `Ingest.write`'s rollback or `Storage.applyRetention`, or touches `desktop/storage/src/test/kotlin/appmonitor/desktop/storage/StorageOpenTest.kt`"
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

The first PR that changes `Ingest.write`'s rollback or `Storage.applyRetention`, or touches `desktop/storage/src/test/kotlin/appmonitor/desktop/storage/StorageOpenTest.kt`.

Re-triggered once during PLAN-0006 because its review tasks rewrote the
rollback and close code. Fired again by PLAN-0007 M6.3, whose `Storage`
change is read-only and whose `Ingest` change is one queued settings
write: none of the rollback, allocation, retention or test code these
findings name changed, and rewriting `Ingest`'s rollback inside an MCP
feature PR mixes two risks. Narrowed to that code.

Fired by PLAN-0008 M7.2, whose `Ingest` change adds two queued issue
items (their rollback is one line: the outcome cleared), whose
`applyRetention` change is the `prune` call, and whose `StorageOpenTest`
change is the expected table list and schema version. The rollback,
allocation, idle-pass cleanup and `/tmp` test these findings name are
unchanged. Re-triggered past PLAN-0008, whose milestone review reads
this code.

Fired by PLAN-0009 M8.4, whose `Ingest` change is one line in `network`
(ADR-0104): the rollback, the batch copies, the idle-pass cleanup and the
`/tmp` test are untouched. Narrowed to that code.

Fired by PLAN-0010 M9.2 (`feat/interaction-channel`), whose rollback
change is one line (a mock write's outcome cleared, as M7.2's issues)
and whose `StorageOpenTest` change is the expected table list. The
rollback, allocation, idle-pass cleanup and `/tmp` test are unchanged.

## How it was paid

> Filled when closing.
