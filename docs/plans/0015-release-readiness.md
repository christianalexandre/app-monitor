---
id: PLAN-0015
title: Release readiness — what publishing makes expensive
status: Done # Draft | Active | Done | Abandoned
milestone: H5
started: 2026-10-30
finished: 2026-11-03
---

# PLAN-0015 — Release readiness (H5)

## Goal

Everything that costs more once an artifact is on Maven Central or a
SwiftPM tag is settled: the release gate is green and stable, the
protocol carries what M10 will freeze, a device trusts only the desktop
its developer chose, release builds keep their guarantees, and the SDKs
have run on a physical iPhone. Every DEBT below is paid, or `Dropped`
with its reason, in the PR of its task.

The owner's field test (2026-10-30) found the SDKs worth publishing.
PLAN-0016 (H6) pays the rest of the backlog after this plan, before M10.
This plan becomes `Active` once PLAN-0014 closes.

## Tasks

- [x] **H5.1 A release gate that finishes, in time.** (`ci/release-gate-cache`)
  gRPC-Core leaves `make ios-sample` and `make e2e-ios`: the sample's
  gRPC-Core call through the proxy moves to `make e2e-ios-grpc`, a release
  gate job of its own, the only one that builds it (owner, 2026-10-30;
  ADR-0163, pays DEBT-0080); a cache only if that job needs one to stay
  under its timeout. The ruleset's required checks are compared with the
  workflow's job names by a script in `make scripts-test` (DEBT-0048).
  `pr-conventions.yml` checks a `release/*` → `main` PR the way M10 will
  open it (DEBT-0050).
  Accept: `make e2e-ios`, `make e2e-ios-grpc`, `make ios-sample` and
  `make scripts-test` green locally, their times in the PR; three
  `release-gate.yml` runs, dispatched by the owner, all jobs green,
  `e2e-ios-grpc` under its timeout; the job times in the PR.
  Done: #323; release gate runs 41141294007, 41145340850 and 41148911458
  on its head, all green, `e2e-ios-grpc` 39, 24 and 38 min of 50.
- [x] **H5.2a The fake client's close reason.** (`test/flaky-tests-at-their-cause`)
  Split from H5.2: DEBT-0032 and DEBT-0056 are one cause, a test desktop
  that resets the connection after its close; reproduced on Linux with a
  resetting peer, the test desktop now reads until the client's close.
  Accept: `HandshakeFailureTest` and `MainTest` 50 times green locally.
- [x] **H5.2b The server tests' taken port.** (`test/server-tests-bind-their-own-port`)
  Split from H5.2: DEBT-0094. The `desktop/server` tests that picked a
  port with `ServerSocket(0)` and bound it again failed under a held
  ephemeral range; they now bind port 0 and read the port bound.
  Accept: the three test classes 50 times green locally, and green under
  the held range where the old ones failed.
  Done: 50 of 50 green; under the held range the old tests failed 3 runs
  of 3, the changed ones passed in each (DEBT-0094).
- [x] **H5.2c The stalled desktop breakpoint test's stall.** (`test/stalled-breakpoint-test-awaits-stall`)
  Split from H5.2: DEBT-0110. One drop proved only a burst the writer
  could catch up with, and then wrote the hit to a desktop that never
  answers; the test now waits until a pause lets the writer take nothing.
  Accept: the test 50 times green locally under CPU load, where the old
  one failed.
  Done: under ten `yes` processes the old test failed 3 of 18 runs, the
  new one passed 50 of 50 (DEBT-0110).
- [x] **H5.2d The desktop's close, written and read back.** (`fix/server-close-before-hanging-up`)
  Split from H5.2: DEBT-0066. On an oversized frame Ktor only queued its
  1009 and the handler returned at once, so on Linux the close could be
  lost; after its own closes the desktop closed TCP with the device's
  frames unread, as the test desktop of H5.2a did. The handler now writes
  Ktor's close before it returns, and reads until the device's close,
  bounded at 1 s, after its own.
  Accept: `./gradlew :desktop:server:test`; a test failing on the old
  server shows it reads until the client's close; the oversized frame
  loop in a Linux container, old against new.
  Done: the new test fails on the old server; in a Linux container the
  old server hit EOF in 1 of 700 runs, the new one in 0 of 1000 (DEBT-0066).
- [x] **H5.2e Spotless reads sources only.** (`docs/spotless-reads-sources-only`)
  Split from H5.2: DEBT-0017. Reproduced the failure with the target it
  was seen under (`target("src/**/*.kt")` with
  `targetExclude("**/build/**")`) while files are written and deleted in
  `desktop/app/build/`; the `fileTree("src")` target #148 put in its place
  is run under the same load.
  Accept: the old target failing under that load, the current one 50
  times green under it.
  Done: the old target failed 3 of 10 runs with the DEBT's
  `NoSuchFileException` on a churned file; the current one passed 50 of
  50, the root project's string targets 20 of 20 (DEBT-0017).
- [x] **H5.2f One LAN filter, without tunnels.** (`fix/lan-filter-drops-tunnels`)
  Split from H5.2: DEBT-0036 and DEBT-0005. The advertiser already
  dropped point-to-point interfaces; the fake client's copy and
  `LifecycleTest.bindsLoopbackOnly` did not. All three use
  `LanAddresses` now (ADR-0164). No VPN on the loop's Mac: `make check`
  with one up moved to DEBT-0036's trigger.
  Accept: `LanAddressesTest` rejecting a tunnel's flags; the mDNS,
  discovery and loopback tests 50 times green locally; `make check`.
- [x] **H5.2g Close bounds its wait for the retention pass.** (`fix/storage-close-bounds-retention`)
  Split from H5.2: DEBT-0091. The second hang left a stack:
  `closeGivesUpAfterTimeout` waited in `Storage.close` for the retention
  pass at open, queued on the writer behind the test's held writer, which
  the test releases only after `close` returns. `close` now waits for the
  pass within its timeout.
  Accept: a test holding a retention pass on the writer failing on the
  old `close`; `WriteQueueTest` 50 times green under CPU load.
- [x] **H5.2 A gate that fails only on a real failure.** (`test/settings-ui-test-repeated`)
  Each test that failed once on CI is reproduced (repeated runs, a held
  port, a loaded runner) and fixed at its cause, never skipped: DEBT-0065.
  Accept: each test run 50 times in a row green locally, its command and
  count in the PR.
  Done: H5.2a to H5.2g paid the rest. DEBT-0065's settings timeout did not
  reproduce: the current test and the stored path #187 timed out on, 50 of
  50 each under CPU load and 10 of 10 each in a two-processor cold JVM;
  dropped, the session it waited for is read from memory since #277.
- [x] **H5.3a The app name on the wire.** (`feat/hello-app-name`)
  Split from H5.3: DEBT-0024. `Hello.app_name` carries
  `AppMonitorOptions.appName` from both SDKs; the desktop names the app
  by it, else by `app_id` (ADR-0165).
  Accept: `./gradlew :protocol:allTests :sdk:core:allTests
  :desktop:feature:traffic:test :desktop:app:test`; fixtures regenerated
  and mirrored in `Samples.swift`, `make swift`; `make e2e-android` and
  `make e2e-ios` asserting the app name.
  Done: the suites, `make check` and `make e2e-android`, `make e2e-ios`
  and `make e2e-kmp` green, each asserting its sample's name (DEBT-0024).
- [x] **H5.3b Drop counts on the wire.** (`feat/delivery-report`)
  Split from H5.3: DEBT-0023, its wire half. Both SDKs send a `core`
  `DeliveryReport` (evicted, oversized, the previous session's written
  frames); `AppMonitorServer` turns it into `DeviceSession.drops`, lost
  included, and the headless server logs it (ADR-0166).
  Accept: `./gradlew :protocol:allTests :sdk:core:allTests
  :desktop:server:test`; fixtures regenerated and mirrored in
  `Samples.swift`, `make swift`; `make e2e-android`, `make e2e-ios` and
  `make e2e-kmp` asserting a drop count of zero.
- [x] **H5.3c Drops in the desktop window.** (`feat/drops-in-devices-pane`) Split from H5.3: DEBT-0023's
  rest. The store keeps each session's `DeviceSession.drops`, and the
  devices pane shows a device's when one is not zero, lost unknown said so.
  Accept: `./gradlew :desktop:storage:test :desktop:app:test`, a screenshot
  of a device with drops.
  Done: migration 8 adds `sessions.drops_*`; a real server's report shown
  as "Dropped: 12 buffer full, 1 too large, lost unknown" (`drops.png`,
  DEBT-0023).
- [x] **H5.3 The protocol M10 freezes.** (`fix/server-owns-websocket-close`) Before the first published SDK,
  the wire carries what a later version cannot add without breaking an
  old one (the app name in H5.3a, drop counts in H5.3b); a frame the
  desktop rejects after the peer's close reaches the client as that
  rejection (DEBT-0014); a lagging collector no longer stops the server
  reading pongs, so a reachable device is not closed with 1011
  (DEBT-0022). New fields are optional: an SDK without them still
  connects.
  Accept: `./gradlew :protocol:allTests :sdk:core:allTests
  :desktop:server:test`, `make swift`; the app name and drop count
  assertions of H5.3a and H5.3b still green in `make e2e-android` and
  `make e2e-ios`.
  Done: the device route is a raw Ktor session read by `DeviceConnection`
  (ADR-0167); a rejection before the close got 1000 from the old server,
  1002 now (DEBT-0014). DEBT-0022 did not happen on Ktor 3.6.0, whose
  channels are unbounded; a test pins it, the memory it costs is DEBT-0113.
- [x] **H5.4 A device trusts only the desktop its developer chose.** The
  SDK stops connecting to the first `_appmonitor._tcp` it finds
  (DEBT-0020). The ADR weighs pinning the first desktop approved against
  a pairing code; an option that adds a setup step or changes the public
  API without a standing owner decision is `Proposed`, and the iteration
  stops there.
  Accept: a second desktop on the LAN (`make server` on another port)
  is refused by an SDK paired with the first, on Android, KMP and iOS;
  `./gradlew :sdk:core:allTests`, `make swift`, `make e2e-android`,
  `make e2e-ios`.
  Done: ADR-0168, accepted by the owner (2026-10-31) after a first PR
  stopped on it as `Proposed`: the device pins the first `desktop_id` a
  discovered desktop sends (TXT `id`, `HelloAck.desktop_id`), a manual
  `host` neither checks nor pins. Refusal tested with two stand-in
  desktops in `sdk/core` (JVM, Android host, iOS simulator) and
  `swift test`; the end to ends keep their manual host. DEBT-0020 paid,
  two desktops of one developer is DEBT-0115.
- [x] **H5.5 Release builds keep their guarantees.** (`fix/release-builds-keep-guarantees`) The no-op check sees a
  public no-arg secondary constructor missing from a dump (DEBT-0026).
  The Gradle plugin rewrites `newWebSocket` only for an
  `appmonitor-okhttp` that has it, and fails the debug build with one
  line otherwise (DEBT-0099). The Kotlin an app needs to compile against
  the SDK is settled before it is published: lowered, or stated with its
  reason (DEBT-0108).
  Accept: `./gradlew :sdk:no-op:check` failing on a crafted constructor
  dropped from a no-op; `./gradlew -p gradle-plugin check
  :gradle-plugin:functionalTest` with a consumer forcing an older SDK.
  Done: `ConstructorMatchTest` reads constructors from the JVM classes; a
  crafted `Probe` failed it while `ApiMatchTest` passed (DEBT-0026). The
  plugin rewrites OkHttp only for its own SDK version, a forced 0.0.1
  fails the debug build (DEBT-0099). The SDK compiles to Kotlin 2.3: an
  app on Kotlin 2.2.0 compiles against it, in `make publish-dry-run`
  (DEBT-0108, ADR-0169); the iOS klib floor is DEBT-0117.
- [x] **H5.6 The iOS SDK on the iOS it declares.** (`test/ios-sdk-on-its-lowest-ios`, DEBT-0111) The SDK's tests, mocks
  and breakpoints included, run on the lowest iOS `Package.swift` declares,
  or that floor is raised to what runs (DEBT-0111). No end to end on a
  physical iPhone (owner, 2026-10-30: the focus is the SDK and the
  desktop): the H4 field test ran the Swift SDK on an iPhone 8, so
  DEBT-0041 and DEBT-0052 are paid by it or dropped, with that reason.
  Accept: `swift test` or `make swift-sim` on that iOS's simulator, its
  version in the PR; a mocked and a held call answered there.
  Done, below the floor: macOS 26 refuses the iOS 15.0 runtime, so the
  floor stays iOS 15 untested and the README states the lowest iOS tested
  (ADR-0171, the owner's decision, 2026-11-03; DEBT-0111 stays open). The
  package's suite runs on the simulator (ADR-0170), green on iOS 26.5 and
  16.0, mocks and breakpoints included. iOS 16.0 found a `Location` sent
  unmasked, fixed. DEBT-0041 paid by the field test, DEBT-0052 dropped
  (ADR-0158); no gate runs iOS 16, DEBT-0118. DEBT-0043 and DEBT-0044 fired
  and were paid here, DEBT-0055 re-triggered, DEBT-0119 one test failure.

- [x] **H5.R1 A reconnect's lost count.** (`fix/lost-count-after-fast-reconnect`) A device back within seconds
  finds its old socket still open on the desktop: `lost` stayed null for the
  rest of the SDK's start, so what the blip lost was never shown (ADR-0166).
  Accept: `./gradlew :desktop:server:test :desktop:app:test`; a test with both
  sockets open, failing when a pending count never settles.
  Done: `LostLedger` keeps the count pending until the previous session closes
  (ADR-0172); `TrafficStore` stores it with the app idle.
- [x] **H5.R2 A refused desktop is said so.** (`fix/refused-desktop-logged`) On a physical device, mDNS answers
  from a desktop other than the pinned one are dropped with no log, so the
  discovery times out and backs off for good: a device moved to another desktop
  (ADR-0168, consequence A) shows nothing, and the refusal line is only reached
  through a simulator's or emulator's fallback host. `sdk/core` and the Swift SDK
  log it once per start, and the Swift SDK's `Logger` lines are `.public`, as
  outside a debugger they read `<private>`.
  Accept: `./gradlew :protocol:allTests :sdk:core:allTests`, `make swift`, a test
  in each SDK of an answer refused and logged once; `log show` with the message
  readable on a build not attached to a debugger.
  Done: both SDKs log the first answer whose TXT shows another desktop, once
  per client; the sample without a debugger reads it in `log show`. The
  simulator redacts nothing, so `.public` was proved on macOS, old form
  `<private>` (ADR-0173); no physical iPhone was run. Added: the first line to
  reach `sdk/core`'s iOS `platformLog` in its tests segfaulted, its `NSLog`
  vararg; fixed here.

## Outcome

### H5 milestone review

`code-review` at `medium` of `e47d580..30dda93` (#317 to #341). Ten findings,
two that normal use hits:

| # | Finding | Normal use hits it? | Where |
| --- | --- | --- | --- |
| 1 | A fast reconnect leaves `lost` null for the rest of the SDK's start. | Yes: wrong data, on any Wi-Fi blip | H5.R1 |
| 2 | A refused desktop is dropped with no log on a physical device. | Yes: no connection and no reason, after a device moves desktop | H5.R2 |
| 3 | The SDK's `Logger` lines are redacted outside a debugger. | Yes: makes finding 2's line unreadable from the home screen | H5.R2 |
| 4 | "Clear all data" drops `desktop.id` before writing it back. | No: needs a crash between the two | DEBT-0120 |
| 5 | The pin file is not excluded from backup. | No: needs a phone restored with a debug build | DEBT-0120 |
| 6 | `Storage.close` can wait about twice its bound. | No: needs a stalled writer at shutdown | DEBT-0120 |
| 7 | An oversized drop's report waits for the next envelope. | No: needs an idle app whose last envelope is too large | DEBT-0120 |
| 8 | "Envelope before HelloAck" closes without draining. | No: no SDK sends before `HelloAck`; fixed in H5.R1, the file being touched | H5.R1 |
| 9 | `trailer_check.sh` passes a range `git log` cannot read. | No: CI fetches the base | DEBT-0120 |
| 10 | Three 5-line KDoc blocks. | No: no behavior; `DeviceConnection.kt` fixed in H5.R1 | DEBT-0120 |

- **Shipped**:
  - H5.1: gRPC-Core in a release gate job of its own, the rulesets' checks
    compared with the jobs' names (#323, closed by #326 after three green runs).
  - H5.2: tests that failed once on CI, each at its cause (#327 to #334):
    the close before a hang up, taken ports, a stalled writer, Spotless, one
    LAN filter, `Storage.close`'s bound.
  - H5.3: what M10 freezes on the wire: the app name (#335), drop counts
    (#336) and their place in the window (#337), and the server owning the
    WebSocket close (#338).
  - H5.4: a device pins the first desktop that approved it (#339).
  - H5.5: the no-op check reads constructors, the plugin refuses another SDK
    version, an app on Kotlin 2.2.0 compiles against the SDK (#340).
  - H5.6: the iOS SDK's suite on the simulator, green on iOS 26.5 and 16.0,
    which found a `Location` sent unmasked (#341).
  - H5.R1: a reconnect's lost count settled (#342). H5.R2: a refused desktop
    logged, in lines readable outside a debugger (#343).
  - Outside the tasks: a held iOS task's start sent before its end (#324, #325);
    a client refusing the proxy's leaf after its handshake stored (#344), which
    had turned H5.R2's `check` red.
- **Decisions**: ADR-0163 to ADR-0173. ADR-0168 and ADR-0171 are the owner's
  (2026-10-31 and 2026-11-03).
- **Debt**:
  - Paid: DEBT-0005, 0014, 0017, 0020, 0022, 0023, 0024, 0026, 0032, 0041, 0043,
    0044, 0048, 0056, 0066, 0080, 0091, 0094, 0099, 0108, 0110, 0112.
  - Dropped: DEBT-0052 (ADR-0158), DEBT-0065 (did not reproduce).
  - Created and open: DEBT-0113 to DEBT-0122, this review's findings 4 to 7, 9
    and 10 in DEBT-0120.
  - Still open, against the Goal: DEBT-0036 (needs `make check` with a VPN up;
    the loop's Mac has none), DEBT-0111 (macOS 26 refuses the iOS 15.0 runtime,
    ADR-0171), DEBT-0055 (re-triggered), DEBT-0050 (touched by #323, its trigger
    is the next PR on `debt_check.sh`).
  - DEBT-0098 found its cause and is fixed (#344): curl 8.23 refuses the leaf after
    its handshake. It stays open until the release gate's Windows job runs the
    test. DEBT-0122: `:desktop:proxy:test` was `FROM-CACHE`, so the runner's curl
    failing it went unseen.
  - No correctness finding that normal use hits is left open.
- **Deviations**:
  - H5.2 split into H5.2a to H5.2g and H5.3 into H5.3a to H5.3c, one cause or
    one wire change per PR; the plan ran to 18 tasks, past the four to six
    the rules ask for.
  - H5.6 closed below its Accept: the floor stays iOS 15 untested, the owner's
    decision (ADR-0171). No gate runs iOS 16 (DEBT-0118).
  - H5.R2's Accept ran on the simulator and on macOS: no physical iPhone was
    available (DEBT-0121).
  - DEBT-0022 did not happen on Ktor 3.6.0: a test pins it, its memory cost is
    DEBT-0113.
  - #344 was added when H5.R2's `check` went red on a test its PR did not touch.
- **Lessons**:
  - A once-on-CI failure can be reproduced with load, a held port or a Linux
    container: most of H5.2 was. A green CI can be a cached one: when a test goes
    red on a PR that did not touch it, look for `FROM-CACHE` in the runs before
    calling it a flake.
  - The iOS 16 simulator found a bug the runs on iOS 26 did not. A platform
    floor is worth a run before it is declared.
  - A code path no test reached can crash on first use: `sdk/core`'s iOS
    `platformLog` had never run under its tests.
