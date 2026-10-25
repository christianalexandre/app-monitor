---
id: DEBT-0072
title: Non-correctness findings of the second M6 milestone review
status: Open
date: 2026-10-25
trigger: "The first PR after PLAN-0007 M6.R6 is `[x]` that touches `desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/DiffRuns.kt`, `desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/ToolResults.kt`, `desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/ToCurl.kt` or `desktop/mcp/src/test/kotlin/appmonitor/desktop/mcp/ToCurlTest.kt`, or a new MCP tool"
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

The first PR after PLAN-0007 M6.R6 is `[x]` that touches
`desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/DiffRuns.kt`,
`desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/ToolResults.kt`,
`desktop/mcp/src/main/kotlin/appmonitor/desktop/mcp/ToCurl.kt` or
`desktop/mcp/src/test/kotlin/appmonitor/desktop/mcp/ToCurlTest.kt`, or
adds a new MCP tool (the closing check).

## How it was paid

> Filled when closing.
