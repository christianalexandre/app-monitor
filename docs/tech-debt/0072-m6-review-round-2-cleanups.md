---
id: DEBT-0072
title: Non-correctness findings of the second M6 milestone review
status: Open
date: 2026-10-25
trigger: "The first PR after PLAN-0008 closes that touches `desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/ToCurl.kt`, `desktop/mcp/src/test/kotlin/appmonitor/desktop/mcp/ToCurlTest.kt` or `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/McpController.kt`"
paid_date:
---

# DEBT-0072 — Non-correctness findings of the second M6 milestone review

## What is missing

Findings of the `code-review` of `6172cd4..0a36f7a` that are not wrong
results, crashes, hangs, leaks, races or privacy issues (ADR-0016):

- **The closing check is per tool.** `Storage` reads return their default
  once the store closes, so each tool checks `isClosing` after its last
  read. A closing-aware read, or one check in the shared result wrapper,
  would cover every tool and every read added later.
- **Test seams in production signatures.** `DiffRuns.call` takes a
  `runIds` lambda and `McpController` a `readSwitch` parameter that only
  tests change.
- **HAR warning helper copied.** `harResult` repeats `jsonResult`'s
  warning and `TextContent` code, changing only where the array goes.
- **A `HEAD`'s truncation note replaced.** With a stored body cut by the
  device, `to_curl` says the body was left out but no longer gives the
  original `body_size`.
- **Framing headers asserted in one case.** `ToCurlTest` checks that
  `Content-Length` and `Transfer-Encoding` are absent in that case only;
  a stored `content-length` regression would pass.

## Why it was accepted

None changes what a tool answers today: the closing checks are in place
in every tool, the seams and the copy change how hard the next change is,
the `HEAD` note loses a size the agent can read with `get_request`, and
the filter itself lowercases. M6.R5 and M6.R6 fix the findings that do.

## Trigger

The first PR after PLAN-0008 closes that touches
`desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/ToCurl.kt`,
`desktop/mcp/src/test/kotlin/appmonitor/desktop/mcp/ToCurlTest.kt` or
`desktop/app/src/main/kotlin/appmonitor/desktop/app/store/McpController.kt`.

Fired by PLAN-0008 M7.3 (`feat/save-and-list-issues-mcp`), which adds
`list_issues` and `save_issue` and touches `ToolResults.kt`. It paid three
parts: the closing check is now one check in `McpServer`'s shared wrapper,
after the tool returns or fails, and the per-tool checks are gone (the
closing tests of `diff_runs`, `export_har` and `to_curl` go through the
server); `DiffRuns.call`'s `runIds` seam is gone with it; `harResult` and
`jsonResult` share one helper. Left: `McpController`'s `readSwitch` seam,
the `HEAD` note and `ToCurlTest`'s framing assertions, in files this PR
does not change; re-triggered to those files, past PLAN-0008, whose
milestone review reads this code.

## How it was paid

> Filled when closing.
