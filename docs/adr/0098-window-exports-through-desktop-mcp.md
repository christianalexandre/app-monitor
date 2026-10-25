---
id: ADR-0098
title: The window's HAR and cURL exports call desktop/mcp's code
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0098 — The window's HAR and cURL exports call desktop/mcp's code

## Context

PLAN-0008 M7.6 adds "Export HAR" and "Copy as cURL" to the window, with
the code of the MCP tools `export_har` and `to_curl` (SDD 10). That code is
`internal` to `desktop/mcp` (`ExportHar.kt`, `ToCurl.kt`): it reads
`desktop/storage` and writes JSON with kotlinx.serialization, which
`desktop/storage` does not depend on (`desktop/storage/build.gradle.kts`).
`desktop/app` already depends on `desktop/mcp`
(`desktop/app/build.gradle.kts`). ADR-0097 moved the endpoint grouping to
`desktop/storage` because it is a read of the store, and kept MCP's
`ToolError` out of the window's path.

## Options

1. **Move both formats to `desktop/storage`.** One place for every shared
   read, but the store module gains kotlinx.serialization and owns two
   export formats that are not storage.
2. **A public `Exports` facade in `desktop/mcp`.** `ExportHar.log` and
   `ToCurl.command` split out of the argument parsing; `Exports.har` and
   `Exports.curl` call them and turn a `ToolError` into a public
   `ExportRefused`, so the window never sees an MCP type. No move, no new
   dependency; `desktop/mcp`'s public API grows by three small types.
3. **A new `desktop/export` module.** Cleanest seam, one more build for two
   files while nothing else would live there.

## Decision

Option 2. The window's HAR is `export_har` with `max_entries` 1 000 and
`bodies: full`, refused past the same 32 MiB; the cURL is `to_curl`'s
command as is. `ExportHarTest`'s HAR 1.2 check and `ToCurlTest`'s curl run
move to `desktop/mcp`'s test fixtures, which `desktop/app`'s tests use.

## Consequences

- One HAR writer and one cURL writer: `ExportUiTest` checks the window's
  file and command with the same check and curl run as the MCP tests.
- The window's HAR has no `log._warnings`: lost writes show in the window
  already (SDD 09).
- If another window feature needs more of `desktop/mcp`, Option 3 is the
  way out rather than a growing facade.
