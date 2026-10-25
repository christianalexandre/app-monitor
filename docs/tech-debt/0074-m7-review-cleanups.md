---
id: DEBT-0074
title: Non-correctness findings of the M7 milestone review
status: Open
date: 2026-10-25
trigger: "The first PR that touches `desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/McpServer.kt`, `desktop/app/src/main/kotlin/appmonitor/desktop/app/ui/Export.kt`, `desktop/app/src/main/kotlin/appmonitor/desktop/app/ui/Issues.kt` or `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Ingest.kt` after PLAN-0008 closes"
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
  the restart stores a second issue.
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

The first PR that touches
`desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/McpServer.kt`,
`desktop/app/src/main/kotlin/appmonitor/desktop/app/ui/Export.kt`,
`desktop/app/src/main/kotlin/appmonitor/desktop/app/ui/Issues.kt` or
`desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Ingest.kt`
after PLAN-0008 closes.

## How it was paid

> Filled when closing.
