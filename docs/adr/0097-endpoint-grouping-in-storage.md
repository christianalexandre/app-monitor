---
id: ADR-0097
title: The endpoint grouping lives in desktop/storage
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0097 — The endpoint grouping lives in desktop/storage

## Context

PLAN-0008 M7.4 shows per endpoint benchmarks in the window with the same
numbers as the MCP tool `endpoint_stats`, and M7.5 a run diff with
`diff_runs`' comparison. The grouping (endpoint key, tally, percentiles,
read cap) was `internal` to `desktop/mcp` (`EndpointStats.group`, SDD 10).
`desktop/app` depends on `desktop/mcp`, `desktop/storage` and
`desktop/server`; `desktop/mcp` depends on `desktop/storage`
(`desktop/*/build.gradle.kts`). Two copies would drift: the window and an
agent would disagree on a p95.

## Options

1. **Make `desktop/mcp`'s grouping public.** No move, but the window then
   reads its numbers through the MCP module, and `ToolError` (an MCP
   result) would leak into the window's path.
2. **Move it to `desktop/storage`.** Both modules already depend on it; the
   grouping is a read of `searchExchangeSizes`, which lives there. The MCP
   side keeps what is MCP: the closing check that turns into a tool error,
   and the JSON.
3. **A new `desktop/analysis` module.** Cleanest seam, but one more module
   and build for three small types while nothing else would live there.

## Decision

Option 2: `Endpoint`, `EndpointTally`, `Durations`, `EndpointGroups`,
`Storage.groupByEndpoint` and `percentile` in
`desktop/storage/.../Endpoints.kt`. `EndpointStats` and `DiffRuns` in
`desktop/mcp` and the window's benchmarks call it. p99 is added to
`Durations`, so both get it at once.

## Consequences

- One grouping, one set of numbers; `EndpointStatsTest.knownP99` and
  `BenchmarksUiTest` pin the same crafted run.
- `desktop/storage`'s public API grows by these types; a change to the
  grouping is a change to both consumers.
- M7.5 moves `diff_runs`' comparison the same way when the window needs it.
