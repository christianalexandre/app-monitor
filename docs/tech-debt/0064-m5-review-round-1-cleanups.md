---
id: DEBT-0064
title: Non-correctness findings of the first M5 milestone review
status: Open
date: 2026-10-24
trigger: The first PR that touches `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Storage.kt`, `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Search.kt`, `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/WriteQueue.kt` or `desktop/app/src/main/kotlin/appmonitor/desktop/app/ui/App.kt` after M5.R8 is `[x]` in PLAN-0006
paid_date:
---

# DEBT-0064 — Non-correctness findings of the first M5 milestone review

## What is missing

Findings of the `code-review` of `982f39b..055c62b` that are not wrong
results, crashes, hangs, leaks, races or privacy issues (ADR-0016):

- **Re-reads on every commit.** `SearchQuery` (`Search.kt`) always listens
  to `sessions`, and `Ingest` runs `sessionQueries.touch` once per
  envelope, so every committed batch re-runs the session list, the visible
  exchange page and the open detail (bodies inflated again). Listen to
  `sessions` only when the filter joins it, and touch each session once
  per batch.
- **Size-bound pass commits one session at a time.** The size loop in
  `Storage.applyRetention` deletes one session per autocommit and runs the
  three `PRAGMA`s of `liveBytes()` on each iteration, unlike the chunked
  age loop. Delete chunks in one transaction, then measure again.
- **Comment blocks over 4 lines** (guidelines 02): `Storage.kt` above
  `applyRetention` and `clearAll`, `WriteQueue.kt` above the class,
  `App.kt` at line 85.
- **Mappings written twice.** `Storage.sessions()` copies
  `Sessions.toStored()` by hand, `Storage.exchange()` builds
  `StoredExchange` field by field, `App.kt`'s `durationOf` repeats
  `HttpExchange.durationMs`, and `Frame.sq`, `Run.sq` and `Session.sq`
  each define `lastId`.

## Why it was accepted

No wrong result: the re-reads and the per-session commits cost writer and
reader time, not data, and the store's tests stay inside their bounds
(`TrafficStoreTest`'s 10k requests, `RetentionTest`). Style and
duplication are the guidelines' business, not the milestone's Accept
lines.

## Trigger

The first PR that touches
`desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Storage.kt`,
`desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Search.kt`,
`desktop/storage/src/main/kotlin/appmonitor/desktop/storage/WriteQueue.kt`
or `desktop/app/src/main/kotlin/appmonitor/desktop/app/ui/App.kt` after
M5.R8 is `[x]` in PLAN-0006.

Re-triggered by M5.R2, the first PR to touch `Storage.kt`: M5.R3, M5.R4
and M5.R5 rewrite `close`, `Ingest` and `applyRetention`, the code these
findings name, so paying them first would be undone.
Re-triggered again by the second milestone review: M5.R6 and M5.R7
rewrite `Ingest`'s rollback and `close` once more.

## How it was paid

> Filled when closing.
