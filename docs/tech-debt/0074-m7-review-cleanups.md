---
id: DEBT-0074
title: Non-correctness findings of the M7 milestone review
status: Open
date: 2026-10-25
trigger: "The first PR that changes the tool wrapper's closing check in `desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/McpServer.kt` or adds a seam between a tool's write and its read-back, or moves `SaveIssueDialog`, the issue editor or `IssueList` into `desktop/feature/insights` (PLAN-0012 H2.4) or changes what they do, or the closing checks of `ExportHar.log` or `ToCurl.command`"
paid_date:
---

# DEBT-0074 — Non-correctness findings of the M7 milestone review

## What is missing

Findings of the `code-review` of `44516a4..35f6ac8` that normal use does
not hit (ADR-0094):

- **A committed `save_issue` can be answered as closing.** The tool
  wrapper in `McpServer.kt` replaces any result with the closing error
  when `isClosing` is true after the call, so a write that committed just
  before the store started closing is reported as failed; a retry after
  the restart stores a second issue. `create_mock` (M9.3) has the same
  wrapper: a rule stored just before the close is answered as closing.
- **The window's exports have no closing check.** `ExportHar.log` and
  `ToCurl.command` left theirs to the MCP wrapper; "Export HAR" while the
  store closes writes the exchanges read so far and reports success.
- **A double-clicked Save queues two writes.** `SaveIssueDialog` and the
  editor start a write per click with no in-flight guard; under write
  load two clicks store two issues, or stamp two updates.
- **An unexpected export exception escapes.** `exportHar` in `Export.kt`
  catches `ExportRefused` and `IOException` only; anything else leaves
  "Exporting…" shown and reaches the composition's scope.
- **The window shows `export_har`'s hint.** A HAR past 32 MiB is refused
  with "use bodies: none", an option the window does not have.
- **Unknown exchanges are checked before an unknown issue.** An update of
  an issue deleted meanwhile that also names unknown exchanges answers
  "no exchange with id N".
- **The issue list runs its query twice.** `IssueList` keeps an unfiltered
  `observeIssues()` beside the filtered one, only to find the open issue.
- **`EndpointStats.group` keeps its own closing check** although the
  wrapper maps every closing call; two places hold one rule.

## Why it was accepted

Each needs the store to close during the call, a write slow enough for a
second click, an exception no current path throws, or a race between a
deletion and an edit; the rest are wording, a duplicate query and a
duplicate check. None changes what normal use stores or shows.

## Trigger

The first PR that changes the tool wrapper's closing check in `desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/McpServer.kt` or adds a seam between a tool's write and its read-back, or moves `SaveIssueDialog`, the issue editor or `IssueList` into `desktop/feature/insights` (PLAN-0012 H2.4) or changes what they do, or the closing checks of `ExportHar.log` or `ToCurl.command`.

Fired by PLAN-0009 M8.4, which touches `Export.kt` (the connections
note), `Issues.kt` (the method column's width) and `Ingest.kt` (one line,
ADR-0104). Paid: an unexpected export exception is reported as "Not
exported: …" instead of leaving "Exporting…" shown. Left: the items in
`McpServer.kt`, the issue dialogs and list, and the export closing check
and hint, code this PR does not change.

Fired again by PLAN-0010 M9.3 (`feat/mock-editor`), which only registers
`create_mock` in `McpServer.kt`. Re-triggered on the wrapper itself: the
item needs a seam between a tool's write and its read-back to be tested,
and it is a shutdown race normal use does not hit (ADR-0094).

Fired again by PLAN-0012 H2.1 (`refactor/desktop-design-system`), which
swaps the issue dialogs' and list's Material calls for the design
system's and changes nothing they do. Re-triggered on PLAN-0012 H2.4,
which moves `Issues.kt` into `desktop/feature/insights` behind a view
model: the in-flight guard and the single query belong in that view
model, and written here they would be rewritten one task later.

## How it was paid

> Filled when closing.
