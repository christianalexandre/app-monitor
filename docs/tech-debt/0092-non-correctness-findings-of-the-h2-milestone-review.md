---
id: DEBT-0092
title: Findings of the H2 milestone review that normal use does not hit
status: Open
date: 2026-10-25
trigger: "The next PR that touches `desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/ExportHar.kt`, `desktop/feature/insights/src/main/kotlin/appmonitor/desktop/feature/insights/InsightsViewModel.kt`, `desktop/feature/insights/src/main/kotlin/appmonitor/desktop/feature/insights/IssuesViewModel.kt`, `desktop/feature/breakpoints/src/main/kotlin/appmonitor/desktop/feature/breakpoints/BreakpointsViewModel.kt`, `desktop/domain/src/main/kotlin/appmonitor/desktop/domain/Insights.kt` or `desktop/feature/traffic/src/main/kotlin/appmonitor/desktop/feature/traffic/BodyFormat.kt`"
paid_date:
---

# DEBT-0092 — Findings of the H2 milestone review that normal use does not hit

## What is missing

Findings of PLAN-0012's milestone review (ADR-0094). Each one is paid in
the next PR that touches its file:

- **`ExportHar.kt`, the HAR pages are unbounded.** `httpRows` keeps
  reading pages until it has `max_entries + 1` HTTP rows or the filter's
  matches run out. Before, it made one bounded query. If a filter's
  matches are almost all connections, the export reads all of them, 200
  rows per query.
- **`ExportHar.kt`, the connection count is too high.** The count
  includes connections past the last exported entry. With `max_entries
  = 1` and rows `H1, C1, C2, H2`, the comment says "2 connections left
  out" when neither lies inside the exported span.
- **`InsightsViewModel.kt`, overlapping HAR exports race.** A second
  export does not cancel the first, so whichever finishes last writes
  `exportNote`.
- **`IssuesViewModel.kt`, the filter outlives the dialog.** The status
  and tag filter now stays set after the issue list is closed and opened
  again, including the reopen that follows "Save as issue". The old
  dialog reset it on every open.
- **`BreakpointsViewModel.kt`, the held calls wait on the breakpoints
  query.** Held calls are combined with `observeBreakpoints()`, so if
  that store flow never emits, the held-call dialog does not show.
- **`Insights.kt`, the benchmark thresholds are defined twice.**
  `MAX_GROUPED_EXCHANGES`, `MIN_MOVE_MS` and `MIN_MOVE_PERCENT` copy
  `desktop/storage`'s values. Only `desktop/data`'s equality test keeps
  the two in step.
- **`BodyFormat.kt`, unused imports.** `ByteBuffer`,
  `CharacterCodingException` and `CodingErrorAction` are left over from
  `utf8OrNull`'s move to `desktop/ui`.

## Why it was accepted

None of them shows wrong data, crashes or loses data in normal use:

- The HAR paging only reads the filter's own matches, and those are
  rarely almost all connections.
- The connection note only overstates when `max_entries` cuts the
  export.
- The export race needs two exports in flight at once.
- The kept issue filter is visible in the dialog.
- The breakpoints query is a SQLDelight flow, which emits at once.

PLAN-0012 keeps its review tasks to what normal use hits (ADR-0094).

## Trigger

The next PR that touches one of the files named above. That PR pays the
item for its file and leaves the others here.

`TrafficViewModel.kt` paid in PLAN-0013 H3.1b's PR
(`feat/live-list-window`): a picked id read as gone (no longer listed or
stored) leaves no request picked, so the pane says "Select a request"
(`TrafficViewModelTest`, "an original no longer listed or stored leaves
no request picked").

## How it was paid

> Filled when closing.
