---
id: DEBT-0088
title: Non-correctness findings of the M9 milestone review
status: Paid
date: 2026-10-25
trigger: "The next PR that touches `sdk/ios/Sources/AppMonitor/Replays.swift`"
paid_date: 2026-11-03
---

# DEBT-0088 — Non-correctness findings of the M9 milestone review

## What is missing

Findings of the `code-review` of `ee3b172..c93a2fc` that normal use does
not hit (ADR-0094):

- **Recent requests keep their bodies.** The Swift SDK's `RecentRequests`
  (`Replays.swift`) keeps 200 closures, each holding its task's request and
  upload data, with no byte bound. Paid in `sdk/core` by PLAN-0016 H6.3d below.

Paid in PLAN-0016 H6.3's PR (`fix/masked-values-left-in-an-edit`): a masked value left in
an edited URL or header group goes out as the device's own, in OkHttp, Ktor and iOS, by a
change in each SDK and none in `resumeOf` (ADR-0185, the `restores` of
`protocol/fixtures/masking.json`); `readMockRules`' comment is three lines. `HeldCalls.kt`
and `MockFile.kt`, now in `tools/headless-server`, leave the trigger.

Paid in PLAN-0012 H2.2b's PR (`refactor/desktop-traffic-feature`):
"Show original" read only loaded rows, so an original older than the
loaded pages showed "Select a request"; `TrafficViewModel` now reads it
by id (`TrafficViewModelTest`, "show original reads an original past the
loaded pages").

Paid in PLAN-0013 H3.1's PR (`feat/live-traffic-source`), which touches
`Storage.kt`: `Storage.replayOriginals` reads every original in one join
(`originalsOf`), not one `idOf` per replay row (`ReplayedExchangeTest`).
PLAN-0013 H3.2 (`feat/record-runs`) touched `Storage.kt` again with
nothing left in it here, and took it out of the trigger.

Paid in M9.R2's PR: an empty OkHttp response (`Content-Length: 0`) is
now held at a response breakpoint, as in Ktor and iOS
(`BreakpointTest.anEmptyResponseIsHeldToo`).

Paid in PLAN-0015 H5.3a's PR (`feat/hello-app-name`), which logs the
app name in `Main.kt`: `parseArgs`' comment is one line, its flags by their ADRs.
`Main.kt` left the trigger then; PLAN-0015 H5.3b (`feat/delivery-report`),
which logs the drops there, found it still in the frontmatter and removed it.

Paid in PLAN-0016 H6.3b's PR (`refactor/one-kotlin-capture-flow`), which rewrites
`Breakpoints.kt` and `AppMonitorInterceptor.kt`, both out of the trigger now: a Ktor body
replaced at a breakpoint cancels the original's channel when it was streamed
(`aReplacedStreamedBodyLetsItsOriginalGo`), and a request edit reaches the headers an
`OutgoingContent` carries and the type of any body, a stream's too, through a wrapper
(`aStreamedBodyTakesTheEditOfItsOwnHeadersAndType`, and `EditedContentEnginesTest` on CIO and
OkHttp's wire).

Paid in PLAN-0016 H6.3d's PR (`fix/kotlin-sdk-connect-pin-reports`): `RecentRequests.kt`
keeps its last 200 within 32 MiB of bodies, as each capture module tells it, the newest
staying whatever its size (`bodiesPastTheByteBoundLetTheOldestGo`). It leaves the trigger;
PLAN-0016 H6.4 does the same in `Replays.swift`.

## Why it was accepted

Each one needs a session of hundreds of large uploads, an edited URL that
carries a masked token, or a page made mostly of replays. None
of them is wrong stored data, a crash or a hang in a normal debug
session. The plan's two review tasks, M9.R1 and M9.R2, cover the
findings that normal use does hit.

## Trigger

The next PR that touches `sdk/ios/Sources/AppMonitor/Replays.swift`, which pays the item in it.

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

The last item, `Replays.swift`'s bodies, by PLAN-0016 H6.4a: `RecentRequests` keeps its last 200
within 32 MiB of bodies held in memory, as `sdk/core` does (`bodiesPastTheByteBoundLetTheOldestGo`).
The others are recorded above. Nothing is left of it.
