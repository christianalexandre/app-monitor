---
id: ADR-0138
title: MCP reads through a paged query repository beside the window's
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0138 — MCP reads through a paged query repository beside the window's

## Context

ADR-0133 has `desktop/mcp` read and write through `desktop/domain`'s
repositories, not `desktop/storage` (PLAN-0012 H2.5). The window's
repositories (`RunRepository`, `TrafficRepository`, ADR-0135) observe
flows of the first page: `observeRuns()`, `observeExchanges(filter,
limit)`, and `exchange(id, frameLimit)` flattened into `HttpExchange`.
The read tools need what those do not give (`desktop/mcp/src/main`, read
for this ADR):

- `list_runs` pages runs newest first with their sessions' devices and
  apps (`Storage.runSummaries(beforeId, limit)`);
- `search_requests` pages by `after_id` and names each replay's original
  (`searchExchanges`, `replayOriginals`);
- `get_request` returns `request` and `response` as `null` until stored,
  which `HttpExchange`'s empty defaults cannot tell apart, and pages frames
  by id (`frames(id, afterId, limit)`);
- `endpoint_stats` and `diff_runs` cap the exchanges read
  (`maxExchanges`, a test parameter) and check `isClosing` after the read.

`TrafficRepository` and `RunRepository` have fakes in
`TrafficViewModelTest`; every member added to them is a stub there.

## Options

1. **Add the paged reads to `RunRepository` and `TrafficRepository`.**
   One repository per data type; but the fakes gain six members they never
   use, and the window's interfaces grow MCP's paging.
2. **A `TrafficQueryRepository` in `desktop/domain`** with the paged reads
   and `isClosing`, implemented in `desktop/data` over `Storage`; the
   grouping cap added to `BenchmarkRepository` with the store's default.
   The window's interfaces stay as they are; one more repository.
3. **`desktop/mcp` depends on `desktop/data`** and builds the repositories
   from `Storage` itself. Fewest changes, but `desktop/mcp` still holds a
   `Storage` and the `desktop:storage` dependency H2.5 removes.

## Decision

Option 2. `RunSummary`, `SessionSummary` and `ExchangeDetail` mirror the
store's and keep `RequestStarted` and `ResponseReceived` (ADR-0135);
`CapturedFrame` gains its `id`. `MAX_GROUPED_EXCHANGES`, `MIN_MOVE_MS` and
`MIN_MOVE_PERCENT` are declared in `desktop/domain` for the tools'
descriptions, and `desktop/data`'s tests hold them equal to the store's.
`McpServer` takes an `McpRepositories` (`runs`, `queries`, `benchmarks`)
that `desktop/app`'s `McpController` builds with `desktop/data`.

## Consequences

- The window's fakes are unchanged but for `BenchmarkRepository`'s new
  `maxExchanges` parameter in `InsightsViewModelTest`.
- Three constants exist twice; a drift fails `desktop/data`'s tests.
- H2.5b moves the remaining tools (HAR, cURL, issues, mocks, replays, the
  token, health) the same way and drops `desktop/mcp`'s main
  `desktop:storage` dependency; until then `McpServer` takes both a
  `Storage` and `McpRepositories`, and `export_har` maps the domain filter
  back to the store's.
