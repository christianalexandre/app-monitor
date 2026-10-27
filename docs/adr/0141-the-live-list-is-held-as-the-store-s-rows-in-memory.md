---
id: ADR-0141
title: The live list is held as the store's rows in memory, its ids apart from the store's
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0141 — The live list is held as the store's rows in memory, its ids apart from the store's

## Context

ADR-0140 A keeps the live list in memory, bounded, gone on quit, and
says `desktop/data` serves it and the store behind the same repositories,
so features and MCP do not change their reads. Today every reader
(`TrafficRepository`, `TrafficQueryRepository`, `BenchmarkRepository`,
the HAR and cURL exports over the query repository) takes ids and an
`ExchangeFilter` and reads `Storage` (SDD 09). Exchanges, sessions and
frames are named by their row id across the window, MCP (`get_request`,
`to_curl`, `replay_request` take an id) and issues.

What has to be decided here: where the live list lives and in which
types, how a read knows whether it means the live list or the store, and
its bound.

## Options

1. **An in-memory SQLite database with the store's schema.** Every query
   is reused. But the store closes its connection after each transaction
   (ADR-0085), which drops an in-memory database; its ids start at 1 like
   the file's, so an id does not say which database holds it; and the
   bound needs deletes through the same writer queue.
2. **A class in `desktop/storage` holding the store's row types**
   (`StoredSession`, `StoredExchange`, `ExchangeDetail`, `StoredFrame`,
   `ExchangeSizes`), fed by the same three calls as the store, its ids
   from 2^50. `desktop/data`'s mappers, `groupByEndpoint` and every MCP
   tool work on it unchanged. The `ExchangeFilter` match is written a
   second time, in Kotlin.
3. **Domain types in `desktop/data`.** The proxy, which writes to
   `desktop/storage` today (ADR-0101), would have to depend on
   `desktop/data`, and the grouping and filters would be written again.

## Decision

Option 2.

- **A. `LiveTraffic`** in `desktop/storage` implements `TrafficSink`
  (`connected`, `received`, `disconnected`), which `Storage` implements
  too, so the device server and the proxy feed either. It decodes the
  `network` events as `Ingest` does; other channels, and payloads that do
  not decode, are not kept.
- **B. Ids from `LiveTraffic.FIRST_ID` (2^50)**, one counter for sessions,
  exchanges and frames: far above any row id SQLite gives the file, still
  exact as a JSON number (below 2^53). `LiveTraffic.isLive(id)` tells
  where an id lives, so `get_request` and the window's detail take an id
  alone. A live session's run is 0.
- **C. Which one a read means.** A filter with a session reads where that
  session lives; else one with a run reads the store, one without the
  live list. A read by id goes by B. Runs, run summaries, run diffs,
  health and issues are the store's. A live exchange's mock rule name is
  read from the store's rules.
- **D. The bound** is 10 000 exchanges and 128 MiB of bodies and frame
  payloads; past either, the oldest exchange goes, and an ended session
  with it once it holds none. Clear empties the list and keeps the
  connected sessions.
- **E. Repositories.** `Storage.trafficRepository(live)`,
  `trafficQueryRepository(live)` and `benchmarkRepository(live)` route by
  C; the store-only ones stay for what reads the store alone.
- **F. Split.** PLAN-0013's H3.1 lands in two PRs: this source and its
  repositories (H3.1), then the device server, the proxy, the window and
  MCP switched to it with Clear and no run at launch (H3.1b).

## Consequences

- No reader changes its signature; an `ExchangeFilter` with no run now
  means the live list wherever the routed repositories are wired, where
  it meant every stored run.
- Search over the live list walks at most 10 000 rows in memory under a
  lock; the window's observed reads run on `Dispatchers.Default`.
- `LiveTraffic`'s filter match must follow `SearchQuery`'s (ADR-0087);
  `LiveTrafficTest` holds the cases.
