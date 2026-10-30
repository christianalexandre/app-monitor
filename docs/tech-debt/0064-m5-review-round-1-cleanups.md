---
id: DEBT-0064
title: Non-correctness findings of the first M5 milestone review
status: Open
date: 2026-10-24
trigger: "The first PR that touches `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/WriteQueue.kt`, changes `Storage.applyRetention`, or changes how `Ingest` applies a `Received` item or adds a `lastId` query"
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
  the class. (The two in `Storage.kt` were paid by M6.3, the one in
  `App.kt` by M6.8.)
- **Mappings written twice.** `Storage.exchange()` builds
  `StoredExchange` field by field, and `Frame.sq`, `Run.sq` and
  `Session.sq` each define `lastId`. (`Storage.sessions()`'s hand copy of
  the session mapping was paid by M6.3; `App.kt`'s `durationOf`, now the
  one `HttpExchange.durationMs` uses, by M6.8.)

## Why it was accepted

No wrong result: the re-reads and the per-session commits cost writer and
reader time, not data, and the store's tests stay inside their bounds
(`TrafficStoreTest`'s 10k requests, `RetentionTest`). Style and
duplication are the guidelines' business, not the milestone's Accept
lines.

## Trigger

The first PR that touches `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/WriteQueue.kt`, changes `Storage.applyRetention`, or changes how `Ingest` applies a `Received` item or adds a `lastId` query.

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

Fired by PLAN-0007 M6.8 (`feat/mcp-app-wiring`), which adds the MCP
settings to `App.kt`: it paid the two parts in that file (the long comment
and `durationOf`); `App.kt` leaves the trigger.

Fired by PLAN-0008 M7.2 (`feat/issues-in-store-and-window`), which adds
issue items to `Ingest` and routes each retention candidate through
`prune` (ADR-0096). The `WriteQueue.kt` comment is already within four
lines: paid. The size loop still commits one session per transaction and
measures after each; chunking it changes how far past 90 % a pass deletes,
which M7.2's retention tests pin, so it is not mixed into the issue
schema. M7.2 adds one more `lastId` (`Issue.sq`) to the mappings item.
Re-triggered past PLAN-0008, whose milestone review reads this code.

Fired by PLAN-0009 M8.4 (`feat/desktop-connection-exchanges`), whose
`Ingest` change is one line in `network` (status 0 stored as null,
ADR-0104): the session touch, the size loop and the `lastId` copies are
untouched, and reworking them in a reader PR mixes a write-path risk into
it. Narrowed to the code the items name.

Fired by PLAN-0009 M8.7 (`feat/desktop-grpc-status`), whose `Ingest`
change is again one column in `ResponseReceived`'s branch (ADR-0107), and
which adds that column to `Storage.exchange()`'s mapping: the touch, the
size loop and the `lastId` copies stay, for the reason M8.4 gives; the
mapping item is unchanged in kind. Kept as is.

Fired by PLAN-0010 M9.2 (`feat/interaction-channel`), which adds the
`mock_rules` table with its own `lastId` (`Mock.sq`) and two queued mock
items to `Ingest`: the session touch, the size loop and `WriteQueue` are
untouched, and one shared `lastId` would move every insert's id read in
a PR about a new table. One more copy for the mappings item.

Fired by PLAN-0010 M9.7 (`feat/breakpoint-channel`), which adds the
`breakpoint_rules` table with its own `lastId` (`Breakpoint.sq`) and two
queued breakpoint items, for M9.2's reason. One more copy for the
mappings item; the rest untouched.

Fired by the PR that closes the write queue when its writer ends with an
`Error` (`fix/proxy-switch-leaf-renewal-writer-error`, an external
review's T5). Its `WriteQueue.kt` item, the long comment, was already
paid; the rest names `Ingest`, `applyRetention` and the `.sq` files,
which it does not touch. Kept as is.

## How it was paid

> Filled when closing.
