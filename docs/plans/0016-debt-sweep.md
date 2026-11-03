---
id: PLAN-0016
title: Debt sweep before distribution
status: Active # Draft | Active | Done | Abandoned
milestone: H6
started: 2026-11-03
finished:
---

# PLAN-0016 — Debt sweep before distribution (H6)

## Goal

M10 starts with a backlog where every open DEBT waits on something
outside this repository or on the owner. Each DEBT below is paid, or
`Dropped` with its reason, in its task's PR. A trigger that has fired
before and was put off ("the next PR that touches …") is not put off
again: the item is paid, dropped, or its trigger becomes an event that
has not happened yet.

Follows PLAN-0015 (H5). A task may split into several PRs, one per
module or per DEBT, while the plan is `Active`.

Left open, each waiting on an event outside the repository or on the
owner: DEBT-0001 (a JmDNS release), DEBT-0019 (a concrete case for body
masking), DEBT-0029 (OkHttp exposing pings), DEBT-0046 (the hosted
runner's local network), DEBT-0069 (the owner's MCP client), DEBT-0076
(the owner's Instruments finding).

## Tasks

- [x] **H6.1 Desktop server and discovery.** (#346) One LAN interface filter
  for the server and the fake client (DEBT-0005); one interface that
  fails JmDNS no longer stops the server, and zero advertised addresses
  is reported (DEBT-0007); a taken port is one line, not a stack trace
  (DEBT-0010); one host name per address (DEBT-0012); the stop bound
  covers a slow JmDNS close (DEBT-0015); a second desktop on the same data
  directory is refused (DEBT-0062); the headless server in its own
  module, so `desktop/server` no longer depends on the proxy (DEBT-0105);
  the fake client waits for an approval (DEBT-0028). DEBT-0005 was already
  paid, by H5.2f. Measuring the close found DEBT-0015's trigger already met
  here, and browsing the LAN opened DEBT-0123. CI found `ackThenClose`
  answering the client's close with `NORMAL` when it arrived first (forced
  by a 50 ms pause: 40 of 40 returned the ack, 0 of 40 on a raw session),
  also red on `dev` in run 41671215315: the test desktop is now raw.
  Accept: `./gradlew :desktop:server:test :desktop:storage:test
  :tools:fake-client:test`; a second `make desktop` on the same
  `APPMONITOR_DATA_DIR` shows the refusal.
- [x] **H6.2a The store's write path.** (#347) The
  write queue's edges (DEBT-0106's two `WriteQueue.kt` items, DEBT-0109 item 1)
  and the review findings on `Ingest`, `WriteQueue` and `Storage` (DEBT-0064,
  DEBT-0067, DEBT-0068). Split from H6.2: the size pass measured 4.4 times
  faster, and each debt's dropped items say why in the DEBT.
  Accept: `./gradlew :desktop:storage:test`.
- [x] **H6.2b Search and the live list.** (`refactor/search-live-one-decoding`)
  Text search and "failed only" on an index (DEBT-0063, ADR-0177: compact
  indexes, not FTS5; no match over 100 000 exchanges with real headers, 48 ms
  at most for text); the live list and the store share one decoding and one
  filter table (DEBT-0104); DEBT-0093's items in `Ingest.kt` and `LiveTraffic.kt`.
  Split from H6.2.
  Accept: `./gradlew :desktop:storage:test :desktop:storage:verifySqlDelightMigration`.
- [x] **H6.2c One switch for the MCP server and the proxy.** (`refactor/service-switch`)
  `ServiceSwitch` holds what `McpController` and `ProxyController` repeated
  (DEBT-0102), writing the switch under its lock (DEBT-0106, DEBT-0109 item 3), and
  its tests hold each step on a gate, so `readSwitch` and the 2,000-round races go
  (DEBT-0072, DEBT-0073). An expired CA keeps one leaf per host (DEBT-0106,
  DEBT-0109 item 4). The packaged server says its version (DEBT-0071). Split from H6.2.
  Accept: `./gradlew :desktop:app:test :desktop:proxy:test`.
- [x] **H6.2d The title bar and "Clear all data".** (`fix/title-bar-follows-theme`)
  The title bar follows a picked theme at once, through JNA (DEBT-0095, ADR-0178);
  "Clear all data" keeps the desktop id in its transaction (DEBT-0120, item 1).
  The screenshot asked was not possible, the session has no Screen Recording: a real
  window's `effectiveAppearance` was checked instead, the sight of it is DEBT-0124.
  Split from H6.2.
  Accept: `./gradlew :desktop:storage:test :desktop:app:test
  :desktop:app:titleBarScreenshots`.
- [x] **H6.2e The closing check and the review findings.** (`fix/closing-checks-review-findings`)
  The MCP tools' and the window's exports' closing check in one place, a committed write
  answered saved (DEBT-0074, ADR-0179); the H2 and H3 review findings (DEBT-0092,
  DEBT-0093: `Recorder`'s map dropped, the reason in the DEBT), and the H4 one in
  `ExportHar.kt` (DEBT-0109, item 6). Split from H6.2.
  Accept: `./gradlew :desktop:storage:test :desktop:mcp:test
  :desktop:data:test :desktop:app:test`.
- [x] **H6.2 Mock rule order.** (`feat/move-mock-rules`) "Up" and "Down" move a mock rule,
  and a breakpoint with it, in its app's order, and a device gets the new order
  (DEBT-0082, ADR-0180).
  Accept: `./gradlew :desktop:storage:test :desktop:data:test :desktop:app:test`.
- [x] **H6.3a One masking and one sink.** (`refactor/one-kotlin-masking`) `Masking`,
  `CaptureSink` and the event wrapping in `sdk/core`, each library resolving its own
  `Location` (DEBT-0055, ADR-0181, half of DEBT-0101). DEBT-0044 was already paid, by
  PLAN-0015. Split from H6.3.
  Accept: `./gradlew :sdk:core:allTests :sdk:ktor:allTests
  :sdk:android-okhttp:allTests :sdk:no-op:check`; `make e2e-android` and
  `make e2e-kmp-android`.
- [x] **H6.3b One capture flow.** (`refactor/one-kotlin-capture-flow`) `CallCapture` in
  `sdk/core` takes the mock, hold, edit and failure decisions for both libraries, inline at
  the two points that wait (DEBT-0101, ADR-0182). DEBT-0088's items in Ktor's
  `Breakpoints.kt`: a replaced streamed body lets its original go, and an edit reaches a
  content's own headers and a stream's type. Split from H6.3.
  Accept: `./gradlew :sdk:core:allTests :sdk:ktor:allTests
  :sdk:android-okhttp:allTests :sdk:no-op:check`; `make e2e-android` and
  `make e2e-kmp`.
- [ ] **H6.3 Kotlin SDKs.** Ktor's internal API kept behind one
  seam, or replaced (DEBT-0083); a Ktor engine that wraps OkHttp's is
  captured once (DEBT-0100); a cancelled iOS connect closes its socket
  (DEBT-0053); path operations safe on Apple (DEBT-0059); the M4 and M9
  review findings in `sdk/core` and `sdk/ktor` (DEBT-0058, DEBT-0061,
  DEBT-0088); the H5 ones in `DeviceId.kt` and `MonitorClient.kt` (DEBT-0120,
  items 2 and 4).
  Accept: `./gradlew :sdk:core:allTests :sdk:ktor:allTests
  :sdk:android-okhttp:allTests :sdk:no-op:check`; `make e2e-android`
  and `make e2e-kmp`.
- [ ] **H6.4 iOS SDK.** One generic method hook instead of one per arity
  (DEBT-0103); headers CFNetwork adds at send time (DEBT-0037) and every
  end of a WebSocket (DEBT-0038) captured; a replay safe against a
  session being invalidated (DEBT-0084); breakpoints and the Bonjour
  a breakpoint runs on the simulator (DEBT-0086); a desktop socket
  opened before discovery not reported (DEBT-0090); the M2 review
  findings in `Masking.swift` (DEBT-0043) and the H4 ones in
  `Breakpoints.swift` and `ProtoWire.swift` (DEBT-0109, items 5 and 7), and
  the H5 ones in `Device.swift` and `MonitorClient.swift` (DEBT-0120, items 2
  and 4).
  Accept: `make swift`, `make swift-sim`, `make e2e-ios` and
  `make e2e-kmp-ios`, a held call resumed in the end to end.
- [ ] **H6.5 Proxy.** A WebSocket through the proxy shows its frames
  (DEBT-0077); the pass-through list of ADR-0101 E.1 in Settings
  (DEBT-0078); an HTTP/2 client reaches an HTTP/1.1-only origin
  (DEBT-0079); the M8 review findings in `ClientHandler.kt`,
  `Http2Relay.kt` and `connections.c` (DEBT-0081); a client refusing the
  certificate recorded on Windows (DEBT-0098); a dropped proxy event marks
  its exchange (DEBT-0107).
  Accept: `./gradlew :desktop:proxy:test :desktop:app:test`; the release
  gate's Windows job green on `ProxyServerTest`.
- [ ] **H6.6 Loop, scripts and build.** The loop's watchdog kills the
  session's process group and reports any exit after a kill as a timeout
  (DEBT-0013, DEBT-0016); `make check`'s time measured against
  DEBT-0002's bound, paid or dropped with the number; the M3 review
  findings in `e2e_lib.sh` and the Android sample (DEBT-0049,
  DEBT-0051); the 85 comments citing `SDD NN` (DEBT-0109, item 8), and the
  SDK's permission request beside an app's (item 2); `trailer_check.sh` and
  the two long KDocs (DEBT-0120, items 5 and 6).
  Accept: `make scripts-test`, with a session that traps `TERM` logged as
  a timeout and no child left; `make check` timed on a warm cache, the
  time in the PR; `make e2e-android`.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
