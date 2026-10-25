---
id: DEBT-0064
title: Non-correctness findings of the first M5 milestone review
status: Open
date: 2026-10-24
trigger: "The first PR after PLAN-0007 M6.5 that touches `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/WriteQueue.kt`, `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Ingest.kt` or `desktop/app/src/main/kotlin/appmonitor/desktop/app/ui/App.kt`, or changes `Storage.applyRetention`"
paid_date:
---

# DEBT-0064 — Non-correctness findings of the first M5 milestone review

## What is missing

Findings of the `code-review` of `982f39b..055c62b` that are not wrong
results, crashes, hangs, leaks, races or privacy issues (ADR-0016):

- **Re-reads on every commit.** `Ingest` runs `sessionQueries.touch` once
  per envelope, so every committed batch re-runs the session list and the
  open detail (bodies inflated again). Touch each session once per batch.
  (`SearchQuery` listening to `sessions` when no filter joins it was paid
  by M6.5.)
- **Size-bound pass commits one session at a time.** The size loop in
  `Storage.applyRetention` deletes one session per autocommit and runs the
  three `PRAGMA`s of `liveBytes()` on each iteration, unlike the chunked
  age loop. Delete chunks in one transaction, then measure again.
- **Comment blocks over 4 lines** (guidelines 02): `WriteQueue.kt` above
  the class, `App.kt` at line 85. (The two in `Storage.kt` were paid by
  M6.3.)
- **Mappings written twice.** `Storage.exchange()` builds
  `StoredExchange` field by field, `App.kt`'s `durationOf` repeats
  `HttpExchange.durationMs`, and `Frame.sq`, `Run.sq` and `Session.sq`
  each define `lastId`. (`Storage.sessions()`'s hand copy of the session
  mapping was paid by M6.3.)

## Why it was accepted

No wrong result: the re-reads and the per-session commits cost writer and
reader time, not data, and the store's tests stay inside their bounds
(`TrafficStoreTest`'s 10k requests, `RetentionTest`). Style and
duplication are the guidelines' business, not the milestone's Accept
lines.

## Trigger

The first PR after PLAN-0007 M6.5 that touches `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/WriteQueue.kt`, `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Ingest.kt` or `desktop/app/src/main/kotlin/appmonitor/desktop/app/ui/App.kt`, or changes `Storage.applyRetention`.

Re-triggered three times during PLAN-0006 because its review tasks
rewrote the code these findings name. Fired again by PLAN-0007 M6.3,
which touches `Storage` for read-only additions (`runSummaries`,
settings, `isClosing`): it paid the two parts in that file's code it
touched (the session mapping copy, the two long comments) and narrowed
the trigger to the files and the function the rest name, so read-only
`Storage` additions (M6.4, M6.5) no longer fire it.

Fired by PLAN-0007 M6.5 (`feat/mcp-endpoint-stats`), which touches
`Search.kt`: it paid the part in that file (`SearchQuery` listens to
`sessions` only when a filter joins it). The rest names `Ingest`,
`WriteQueue`, `applyRetention`, `App.kt` and the `.sq` files, which it
does not touch; the trigger is narrowed to them.

## How it was paid

> Filled when closing.
