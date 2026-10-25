---
id: DEBT-0088
title: Non-correctness findings of the M9 milestone review
status: Open
date: 2026-10-25
trigger: "The next PR that touches `sdk/ktor/src/commonMain/kotlin/appmonitor/sdk/ktor/Breakpoints.kt`, `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/RecentRequests.kt`, `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/HeldCalls.kt`, `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Storage.kt`, `desktop/app/src/main/kotlin/appmonitor/desktop/app/ui/App.kt` (or its request detail moving to `desktop/feature/traffic`, PLAN-0012 H2.2), `desktop/server/src/main/kotlin/appmonitor/desktop/server/Main.kt`, `desktop/server/src/main/kotlin/appmonitor/desktop/server/MockFile.kt` or `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/TrafficStore.kt`"
paid_date:
---

# DEBT-0088 — Non-correctness findings of the M9 milestone review

## What is missing

Findings of the `code-review` of `ee3b172..c93a2fc` that normal use does
not hit (ADR-0094):

- **A replaced Ktor body leaves the original open.** In `Breakpoints.kt`,
  `holdResponse` builds a new call over the edited body and does not
  cancel the original's `rawContent` when the body was streamed, not
  saved. The engine's connection is not given back to its pool.
- **Recent requests keep their bodies.** `RecentRequests.kt` keeps up to
  200 replay closures. Each one holds its OkHttp `Call` or Ktor request,
  with its body, until the session ends.
- **A masked query value goes back as `██`.** In `HeldCalls.kt`,
  `resumeOf` sends the whole URL once any part of it is edited. A masked
  query parameter then goes out as the placeholder. The same happens to
  the masked values of a header group when one value of the group is
  edited.
- **Some Ktor request edits are dropped.** In `Breakpoints.kt`, `edit()`
  skips the headers that the `OutgoingContent` carries. It changes
  `Content-Type` only on a `ByteArrayContent`. A `WriteChannelContent`
  goes out unedited.
- **One query per replay row.** `Storage.replayOriginals` looks up each
  row's original with its own `idOf` query. A join would do it in the
  page query.
- **"Show original" can show nothing.** In `App.kt`, the original is
  selected by id, but the detail pane finds only loaded rows. An
  original older than the loaded pages shows "Select a request".
- **Comment blocks longer than four lines** (guidelines 02): `parseArgs`
  in `Main.kt`, `TrafficStore` in `TrafficStore.kt` and `readMockRules`
  in `MockFile.kt`.

Paid in M9.R2's PR: an empty OkHttp response (`Content-Length: 0`) is
now held at a response breakpoint, as in Ktor and iOS
(`BreakpointTest.anEmptyResponseIsHeldToo`).

## Why it was accepted

Each one needs an edited streamed body, a session of hundreds of large
uploads, an edited URL that carries a masked token, a Ktor body that is
not a `ByteArrayContent`, a page made mostly of replays, or an original
past the loaded pages. None
of them is wrong stored data, a crash or a hang in a normal debug
session. The plan's two review tasks, M9.R1 and M9.R2, cover the
findings that normal use does hit.

## Trigger

The next PR that touches one of the files named above. That PR pays the
items in its file:

- `sdk/android-okhttp/src/commonMain/kotlin/appmonitor/sdk/okhttp/AppMonitorInterceptor.kt`
- `sdk/ktor/src/commonMain/kotlin/appmonitor/sdk/ktor/Breakpoints.kt`
- `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/RecentRequests.kt`
- `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/HeldCalls.kt`
- `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Storage.kt`
- `desktop/app/src/main/kotlin/appmonitor/desktop/app/ui/App.kt`, or the PR that moves its request detail into `desktop/feature/traffic` (PLAN-0012 H2.2)
- `desktop/server/src/main/kotlin/appmonitor/desktop/server/Main.kt`
- `desktop/server/src/main/kotlin/appmonitor/desktop/server/MockFile.kt`
- `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/TrafficStore.kt`

`App.kt` fired by PLAN-0012 H2.1 (`refactor/desktop-design-system`),
which swaps its Material calls for the design system's and changes
nothing it does. "Show original" is re-triggered on PLAN-0012 H2.2,
which moves the request list and detail into `desktop/feature/traffic`
behind a view model: reading the original by id belongs there, and
written in `App.kt` it would be rewritten one task later.

## How it was paid

> Filled when closing.
