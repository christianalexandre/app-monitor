---
id: DEBT-0088
title: Non-correctness findings of the M9 milestone review
status: Open
date: 2026-10-25
trigger: "The next PR that touches `sdk/android-okhttp/src/commonMain/kotlin/appmonitor/sdk/okhttp/AppMonitorInterceptor.kt`, `sdk/ktor/src/commonMain/kotlin/appmonitor/sdk/ktor/Breakpoints.kt`, `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/RecentRequests.kt`, `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/HeldCalls.kt`, `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Storage.kt`, `desktop/app/src/main/kotlin/appmonitor/desktop/app/ui/App.kt`, `desktop/server/src/main/kotlin/appmonitor/desktop/server/Main.kt`, `desktop/server/src/main/kotlin/appmonitor/desktop/server/MockFile.kt` or `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/TrafficStore.kt`"
paid_date:
---

# DEBT-0088 — Non-correctness findings of the M9 milestone review

## What is missing

Findings of the `code-review` of `ee3b172..c93a2fc` that normal use does
not hit (ADR-0094):

- **An empty OkHttp response is never held.** In
  `AppMonitorInterceptor.kt`, a response with `Content-Length: 0` (a 204,
  a `HEAD`) returns before `holdResponse`. A response breakpoint on it
  lets it through. Ktor and iOS hold it.
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

## Why it was accepted

Each one needs an empty response under a response breakpoint, an edited
streamed body, a session of hundreds of large uploads, an edited URL
that carries a masked token, a Ktor body that is not a `ByteArrayContent`,
a page made mostly of replays, or an original past the loaded pages. None
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
- `desktop/app/src/main/kotlin/appmonitor/desktop/app/ui/App.kt`
- `desktop/server/src/main/kotlin/appmonitor/desktop/server/Main.kt`
- `desktop/server/src/main/kotlin/appmonitor/desktop/server/MockFile.kt`
- `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/TrafficStore.kt`

## How it was paid

> Filled when closing.
