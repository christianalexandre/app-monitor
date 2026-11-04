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
runner's local network), DEBT-0059 (a kotlinx-io release), DEBT-0069 (the
owner's MCP client), DEBT-0076 (the owner's Instruments finding), DEBT-0098
(the release gate's Windows job, on the next `dev` to `main` PR), DEBT-0049
(the desktop package's version, on M10's first published package).

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
- [x] **H6.3c One seam and a wrapped engine.** (`fix/ktor-wrapped-engine-one-seam`) Ktor's internal
  API in one file (DEBT-0083); a Ktor engine that wraps OkHttp's captured once, known by its
  config (DEBT-0100, ADR-0183). DEBT-0058 dropped, its reason in the DEBT: the desktop reads
  Ktor's `ws`. Split from H6.3.
  Accept: `./gradlew :sdk:core:allTests :sdk:ktor:allTests
  :sdk:android-okhttp:allTests :sdk:no-op:check`; `make e2e-android` and
  `make e2e-kmp`.
- [x] **H6.3d A cancelled connect, the pin and an idle report.** (`fix/kotlin-sdk-connect-pin-reports`)
  A cancelled connect closes its socket on iOS, CIO's own connect timeout off (DEBT-0053,
  ADR-0184); `sdk/core`'s pin left out of iOS backups and an oversized drop reported by an
  idle session (DEBT-0120, items 2 and 4); replay bodies within a byte bound in
  `RecentRequests.kt` (DEBT-0088); the device id's comments and paths under test (DEBT-0061).
  DEBT-0059 moved to *Left open*: kotlinx-io 0.9.2 still calls `dirname`, and `sdk/core`
  calls no such path operation on iOS. Split from H6.3.
  Accept: `./gradlew :sdk:core:allTests :sdk:ktor:allTests
  :sdk:android-okhttp:allTests :sdk:no-op:check`; `make e2e-android` and
  `make e2e-kmp`.
- [x] **H6.3 Kotlin SDKs.** (`fix/masked-values-left-in-an-edit`) A masked value left in an edited URL or header group goes out
  as the device's own, in OkHttp, Ktor and iOS, no change in `HeldCalls.kt` (DEBT-0088,
  ADR-0185), and `readMockRules`' comment in `MockFile.kt` (DEBT-0088), in
  `tools/headless-server` since H6.1.
  Accept: `./gradlew :sdk:core:allTests :sdk:ktor:allTests
  :sdk:android-okhttp:allTests :tools:headless-server:test :desktop:app:test`; `make swift`.
- [x] **H6.4a The Swift SDK's small findings.** (`fix/swift-sdk-replay-pin-reports`) A replay
  racing the app invalidating its session refused, not a crash (DEBT-0084, ADR-0186); replay bodies
  within a byte bound in `Replays.swift`, as in `sdk/core` (DEBT-0088, paid whole); the pin out of
  backups and an oversized drop reported by an idle session (DEBT-0120, items 2 and 4); nested
  messages without their copies (DEBT-0109, item 7). Dropped with their reasons in the DEBTs:
  DEBT-0090, and DEBT-0109's item 5, which a probe found false. DEBT-0043 was already paid, by
  PLAN-0015. Split from H6.4.
  Accept: `make swift`, `make swift-sim`, `make e2e-ios` and `make e2e-kmp-ios`.
- [x] **H6.4b One method hook.** (`refactor/ios-one-method-hook`) `MethodHook` holds what every
  hook repeated, a wrong argument count logged and not hooked (DEBT-0103, ADR-0187). Split from H6.4.
  Accept: `swift test --package-path sdk/ios --filter MethodHookTests`, `make swift`, `make swift-sim`,
  `make e2e-ios` and `make e2e-kmp-ios`.
- [x] **H6.4c A held call in the iOS end to end.** (`test/ios-held-call-end-to-end`) The headless
  server's `--breakpoints FILE` sends breakpoints and resumes each hit; `make e2e-ios` holds the
  sample's upload at both stages and asserts the resume reached the simulator (DEBT-0086,
  ADR-0188). Running `make e2e-ios-grpc` found its trust store ignored since H6.1 renamed the
  server's start script (`SERVER_OPTS`, now `HEADLESS_SERVER_OPTS`): fixed here. Split from H6.4.
  Accept: `./gradlew :tools:headless-server:test`, `make scripts-test`, `make e2e-ios` and
  `make e2e-kmp-ios`.
- [x] **H6.4d Headers sent.** (`fix/ios-headers-sent-at-send-time`) Headers CFNetwork adds
  at send time reach the desktop with the response, from the task's transaction metrics, in
  `ResponseReceived.request_headers` (DEBT-0037, ADR-0189); a request without a network response
  keeps its resume headers (DEBT-0125). Split from H6.4.
  Accept: `swift test --package-path sdk/ios`, `./gradlew :protocol:allTests
  :desktop:storage:test`, `make swift`, `make swift-sim`, `make e2e-ios` and `make e2e-kmp-ios`.
- [x] **H6.4 iOS SDK.** (`fix/ios-websocket-every-end`) Every end of a WebSocket captured, read
  where the task finishes (DEBT-0038, ADR-0190). A peer's close with no `receive` pending is not
  read by the task itself, probed: it is recorded when the app next receives or cancels.
  Accept: `make swift`, `make swift-sim`, `make e2e-ios` and
  `make e2e-kmp-ios`.
- [x] **H6.5a The proxy's flow and its cut exchanges.** (`fix/proxy-flow-and-cut-exchanges`) A
  response ending before its request drops the rest of the body and the upstream connection, each
  side read only while the other takes its writes, per stream over HTTP/2 (DEBT-0081's
  `ClientHandler.kt` and `Http2Relay.kt` items); an exchange the recording dropped an event of is
  cut whole or ended as failed (DEBT-0107, paid). ADR-0191. Split from H6.5.
  Accept: `./gradlew :desktop:proxy:test :tools:headless-server:test`.
- [x] **H6.5b The M8 review's last findings.** (`fix/reused-socket-and-one-status`) A socket
  closed outside the rebound `close` ends its exchange when its number connects again
  (`connections.c`, ADR-0192); the row's and the detail's status in one rule in `desktop/ui`
  (DEBT-0081, paid whole). Split from H6.5.
  Accept: `swift test --package-path sdk/ios --filter ConnectionCaptureTests`,
  `./gradlew :desktop:ui:test :desktop:feature:traffic:test`, `make swift`, `make swift-sim`.
- [x] **H6.5c A WebSocket's frames through the proxy.** (`feat/proxy-websocket-frames`) After a
  `101` to `websocket` each message and control frame is read as it is relayed, unmasked, joined
  and inflated, and stored as a `WebSocketFrame`; no close frame either way ends it failed
  (DEBT-0077, ADR-0193). DEBT-0122 paid on its trigger: the tests' curl version is an input of
  `:desktop:proxy:test` and `:desktop:mcp:test`. Split from H6.5.
  Accept: `./gradlew :desktop:proxy:test`.
- [x] **H6.5d The pass-through list.** (`feat/proxy-pass-through`) Hosts the settings list,
  an exact name or `*.domain`, are tunnelled as raw bytes at their next `CONNECT`, nothing stored
  (DEBT-0078, ADR-0194). Split from H6.5.
  Accept: `./gradlew :desktop:proxy:test :desktop:feature:settings:test :desktop:app:test`.
- [x] **H6.5 Proxy.** (`fix/proxy-http2-client-http1-origin`) An HTTP/2 client reaches an
  HTTP/1.1-only origin: the origin is asked before the client's handshake and the leaf offers what
  it does (DEBT-0079, ADR-0195). DEBT-0098 moved to *Left open*: the loop does not dispatch the
  release gate, so its Windows job, this task's other Accept, runs on the next `dev` to `main` PR.
  Accept: `./gradlew :desktop:proxy:test :desktop:app:test`.
- [x] **H6.6a The loop's watchdog.** (`fix/loop-watchdog-kills-the-tree`) At the deadline the
  session and every process under it get `TERM`, then `KILL`, and that is a timeout whatever the
  exit code (DEBT-0013, DEBT-0016, ADR-0196). The tree, not the process group the task asked: a
  real session's Bash tool runs in its own group. `make check` measured at 45 s warm, DEBT-0002
  dropped with the number; an unknown base fails `trailer_check.sh` (DEBT-0120, item 5). Split from H6.6.
  Accept: `make scripts-test`, with a session that traps `TERM` logged as
  a timeout and no child left; `make check` timed on a warm cache, the
  time in the PR.
- [x] **H6.6 Loop, scripts and build.** (`chore/m3-findings-and-sdd-citations`) `make e2e-android`
  rotates the emulator and kills the process, a session kept and one reconnection (DEBT-0051); no
  source comment cites `SDD NN` (DEBT-0109, item 8), 83 in 62 files; the two long KDocs (DEBT-0120,
  item 6). Dropped with their reasons in the DEBTs: the grant on a preview image, the 37.2 image now
  reporting 37 (DEBT-0049), and the SDK's permission request beside an app's (DEBT-0109, item 2).
  DEBT-0049 moved to *Left open* for its `packageVersion` item, waiting on M10. Added: the headless
  server's `--advertise false`, since an emulator found it by mDNS and M3.R5's fix undone still passed;
  DEBT-0118's run on iOS 16 found a test pinning a newer iOS's error code, fixed here.
  Accept: `make check`; `make e2e-android`.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
