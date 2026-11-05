---
id: DEBT-0071
title: Non-correctness findings of the first M6 milestone review
status: Paid
date: 2026-10-25
trigger: "The first desktop release, or the first PR that changes `Storage.setSetting`"
paid_date: 2026-11-03
---

# DEBT-0071 — Non-correctness findings of the first M6 milestone review

## What is missing

Findings of the `code-review` of `45d382c..6172cd4` that are not wrong
results, crashes, hangs, leaks, races or privacy issues (ADR-0016):

- **A refused settings write is not reported.** `Storage.setSetting`
  ignores `queue.enqueue`'s result. A failed batch is already counted in
  the storage health the settings show; only a write after `close`, when
  no switch or token button is reachable, goes unseen.
- **The packaged server says `dev`.** `McpController` builds `McpServer`
  without a version, so `initialize`'s `serverInfo.version` and the HAR's
  `creator.version` are `dev` in every build.
- **The HAR cap counts characters.** `ExportHar` measures the 32 MiB cap
  with `String.length` and serializes each entry twice (once to measure).
  Non-ASCII bodies can pass the cap in UTF-8 bytes.
- **Header serializer copied.** `ExportHar.toHar` repeats `GetRequest`'s
  header `toJson`, and the enum-name formatting is repeated in
  `GetRequest`, `Filters` and `ListRuns`.

## Why it was accepted

No wrong data an agent acts on: a setting refused only after close, a
version label, a soft size bound, and duplication or work that changes how
hard the next change is, not what a tool answers. M6.R1 to M6.R4 fix the
findings that do.

## Trigger

The first desktop release, or the first PR that changes `Storage.setSetting`.

Re-triggered by PLAN-0007 M6.R5, which touched `McpController.kt` to fix
the switch race: its only item here, the `dev` version, needs a build
version passed to the app, and the first desktop release already forces
it, so the file left the list.

Fired by PLAN-0008 M7.2, which adds the issue calls to `Storage.kt`:
`setSetting`, the only item in that file, is untouched, and the issue
writes return an `IssueWrite` that says `NotSaved` after close. M7.4 and
M7.6 move `EndpointStats` and `ExportHar` code and will meet the other
items; re-triggered past PLAN-0008 so they are paid there or after.
M7.4 moved the grouping to `desktop/storage` (ADR-0097) and paid
"durations sorted per read": `EndpointTally.durationStats` sorts once.

Fired by PLAN-0009 M8.4, which touches `ExportHar.kt`, `GetRequest.kt`,
`EndpointStats.kt` and `Storage.kt`. Paid: one header serializer
(`List<Header>.toJson` in `ToolResults.kt`) for `get_request` and HAR;
the HAR cap counts UTF-8 bytes (`ExportHarTest.theCapCountsUtf8Bytes`);
one `wireName` for the enum values `GetRequest`, `Filters` and `ListRuns`
write. Left: `setSetting`'s refused write and the `dev` version, which the
first release forces.

## How it was paid

Paid by PLAN-0016 H6.2c (`refactor/service-switch`). The packaged server says the build's
`VERSION_NAME`: `desktop/app` passes `-Dappmonitor.version` to `run` and to the
package (read in the `.cfg` of `createDistributable`), and `McpController` gives it
to `McpServer` (`McpWiringTest.theServerSaysTheVersionItWasGiven`). `dev` is left for
tests and IDEs.

Dropped: reporting a refused `setSetting`. It is refused only after `close`, when no
switch is reachable, and a failed batch is already in the storage health.
