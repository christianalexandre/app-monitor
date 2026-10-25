---
id: DEBT-0088
title: Non-correctness findings of the M9 milestone review
status: Open
date: 2026-10-25
trigger: "The next PR that touches `sdk/ktor/src/commonMain/kotlin/appmonitor/sdk/ktor/Breakpoints.kt`, `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/RecentRequests.kt`, `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/HeldCalls.kt`, `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Storage.kt`, `desktop/server/src/main/kotlin/appmonitor/desktop/server/Main.kt`, or `desktop/server/src/main/kotlin/appmonitor/desktop/server/MockFile.kt`"
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
- **Comment blocks longer than four lines** (guidelines 02): `parseArgs`
  in `Main.kt` and `readMockRules` in `MockFile.kt`.

Paid in PLAN-0012 H2.2b's PR (`refactor/desktop-traffic-feature`):
"Show original" read only loaded rows, so an original older than the
loaded pages showed "Select a request"; `TrafficViewModel` now reads it
by id (`TrafficViewModelTest`, "show original reads an original past the
loaded pages").

Paid in PLAN-0013 H3.1's PR (`feat/live-traffic-source`), which touches
`Storage.kt`: `Storage.replayOriginals` reads every original in one join
(`originalsOf`), not one `idOf` per replay row (`ReplayedExchangeTest`).

Paid in M9.R2's PR: an empty OkHttp response (`Content-Length: 0`) is
now held at a response breakpoint, as in Ktor and iOS
(`BreakpointTest.anEmptyResponseIsHeldToo`).

## Why it was accepted

Each one needs an edited streamed body, a session of hundreds of large
uploads, an edited URL that carries a masked token, a Ktor body that is
not a `ByteArrayContent`, or a page made mostly of replays. None
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
- `desktop/server/src/main/kotlin/appmonitor/desktop/server/Main.kt`
- `desktop/server/src/main/kotlin/appmonitor/desktop/server/MockFile.kt`

`App.kt` fired by PLAN-0012 H2.1 (`refactor/desktop-design-system`),
which swaps its Material calls for the design system's and changes
nothing it does. "Show original" is re-triggered on PLAN-0012 H2.2,
which moves the request list and detail into `desktop/feature/traffic`
behind a view model: reading the original by id belongs there, and
written in `App.kt` it would be rewritten one task later. Fired again by
H2.2a (`refactor/desktop-domain-data`), which only reroutes the panes'
reads through `desktop/data`; H2.2 was split and the view model is
H2.2b's, so the item moves to H2.2b.

Fired by PLAN-0012 H2.3 (`refactor/desktop-mocks-breakpoints-features`),
which moves `HeldCall` and `HeldEdit` from `HeldCalls.kt` to
`desktop/domain` and changes nothing `resumeOf` sends. Paying the masked
query item needs each SDK to put its own value back in an edited URL
(OkHttp, Ktor and iOS), not a change in this file; for `HeldCalls.kt`
the trigger becomes the next PR that changes `resumeOf` or the
`BreakpointResume` message.

`TrafficStore.kt` paid in PLAN-0013 H3.1b's PR (`feat/live-list-window`),
which feeds the live list from it: its comment is one line.

## How it was paid

> Filled when closing.
