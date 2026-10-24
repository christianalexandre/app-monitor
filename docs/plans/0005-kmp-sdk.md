---
id: PLAN-0005
title: KMP SDK
status: Active # Draft | Active | Done | Abandoned
milestone: M4
started: 2026-10-24
finished:
---

# PLAN-0005 — KMP SDK (M4)

## Goal

A Kotlin Multiplatform app that calls its backend through a Ktor
`HttpClient` shows that traffic in the desktop from Android and from iOS,
through an `sdk/ktor` plugin on every SDK target; an iOS app running both
`sdk/ktor` and the Swift SDK shows each exchange once (ADR-0003); a KMP
sample proves it end to end.

## Tasks

M3's lessons applied: decisions in the "stop and ask" areas of `AGENTS.md`
come first, in one stop; `sdk/core` on iOS writes its lifecycle cases
(start, stop, background, a second start) and the tests that drive them
before the code; every end to end runs locally as a `make` target before it
runs in the release gate; `make debt-check` decides how each fired DEBT is
handled (DEBT-0027 fires on any `MonitorClient` change).

Owner's requirement for every task: setting the SDK up is a short list of
copy-paste steps, each documented with its exact snippet — the dependency
(Gradle or SwiftPM), a Gradle block for ADR-0062 D.1, the Run Script phase
of ADR-0049, the import and `AppMonitor.start` at the app's entry point. A
step that needs more than pasting a documented snippet goes to a `Proposed`
ADR.

- [x] **M4.1 KMP SDK API, privacy and dedupe decisions.** (ADR-0062, ADR-0063, ADR-0064, ADR-0065 `Accepted`: the owner took every recommendation) `Proposed` ADRs,
  one stop: (a) the public API a KMP developer touches — how the Ktor
  plugin is installed and configured, how `AppMonitor.start` looks from
  common and iOS code (ADR-0017's shape), the Maven artifacts and their
  no-op (ADR-0054, ADR-0026), the Kotlin/Native targets and the oldest Ktor
  version supported; (b) what the plugin captures and masks — every place a
  secret can sit in a Ktor exchange (headers, URL, bodies, WebSocket
  frames), the defaults and the `masking` switch as ADR-0018 and ADR-0034,
  the local network keys a KMP iOS app needs (ADR-0049's script or not);
  (c) the dedupe of ADR-0003 — the correlation header's name and value,
  whether it leaves the device or is stripped before the request is sent,
  and what the Swift SDK does when it sees it; (d) the KMP sample — UI
  toolkit, targets, and whether its end to end joins the release gate
  (runner minutes, ADR-0052).
  Accept: the ADRs merged by the owner with `status: Accepted`.
- [x] **M4.2 `sdk/core` lifecycle on iOS on paper.** (`docs/sdk-core-ios-lifecycle`; ADR-0066 picks CIO and common tests, DEBT-0052) SDD 04 states where
  `sdk/core` on Kotlin/Native differs from the JVM: Ktor engine, the JDK
  calls it replaces, threading, what `stop()` does in each state, app
  background and foreground; each case names the test that will drive it
  (M3's lesson).
  Accept: `make docs-check`; every SDD 04 state has its iOS exits and a
  named test.
- [x] **M4.3 `sdk/core` on iOS targets.** (`feat/sdk-core-ios-targets`; ADR-0067, DEBT-0053. Deviations: iOS
  `AppMonitor.start` moves to M4.4 with the `Hello` it needs; the no-op's iOS dump to M4.8; the iOS
  tests run in `make check` on a Mac only, PR CI is Linux, the gate job is M4.10's; found and fixed a
  hang on every platform, a session after a ping timeout never left connected) The iOS targets M4.1 (a) picks in
  `appmonitor.kmp-sdk` (supersedes ADR-0023's "no iOS artifact"); transport,
  buffer and backoff build and run there per M4.2; ABI dumps updated for
  the new targets.
  Accept: `./gradlew :sdk:core:allTests` runs `iosSimulatorArm64Test` with
  M4.2's cases, not skipped; a live test gated by an environment variable
  reaches `make server` from the iOS simulator.
- [x] **M4.4 `sdk/core` iOS discovery and device identity.** (`feat/sdk-core-ios-discovery`; ADR-0068, DEBT-0054. Deviations: the
  ADR-0042 TCP probe never left preparing on the simulator, so names resolve with `dns_sd`; found and fixed
  `stopWhileConnectedEndsTheDesktopSession`, which returned a `List` and so never ran on JUnit; the live Bonjour run
  connected to the first desktop answered, a leftover headless server on 47999, not the `make server` started for it) Manual host →
  Bonjour from Kotlin/Native → the simulator's `127.0.0.1`, the same
  contract as ADR-0024 and the Swift SDK (ADR-0042); the `Hello` an iOS KMP
  app sends (device id, model, OS, app name); the device id file shared
  with the Swift SDK, and two sessions with one (`device_id`, `app_id`)
  kept open by the desktop (ADR-0064 B.1).
  Accept: `./gradlew :sdk:core:allTests` — discovery with fake browsers on
  the iOS simulator; the live test finds `make server` by Bonjour.
- [x] **M4.5 `sdk/ktor` HTTP capture.** (`feat/sdk-ktor-http-capture`; ADR-0069, DEBT-0055. Deviation: streamed
  bodies, both ways, are sent empty and truncated rather than split) The plugin per M4.1 (a, b) on JVM,
  Android and iOS: request, response, failure, bounded bodies (ADR-0020),
  masking with the shared vector `protocol/fixtures/masking.json`, capture
  layer `ADAPTER` (ADR-0038); SDD chapter for the module.
  Accept: `./gradlew :sdk:ktor:allTests` — the plugin against Ktor's
  `MockEngine` and the masking vector, on JVM and the iOS simulator.
- [x] **M4.6 `sdk/ktor` WebSocket capture.** (`feat/sdk-ktor-websocket-capture`; ADR-0070. Deviations: frames are
  also proven on OkHttp (JVM) and Darwin (simulator), the engines ADR-0063 named; a first relay coroutine lost Darwin's
  close and was replaced; Ktor's Darwin engine itself may fail a session on the peer's close, capture or not) Frames both ways and the close,
  truncation as ADR-0028, if M4.1 (b) keeps it in scope.
  Accept: `./gradlew :sdk:ktor:allTests` — frames captured against an
  in-process echo server on JVM.
- [x] **M4.7 Dedupe with the Swift SDK.** (`feat/ktor-swift-dedupe`; ADR-0071: the in-process mark needs
  `ktor-client-darwin` or new public API, the header stands. Deviation: found and fixed a Swift gap, a WebSocket
  task made from a `ws` `URLRequest`, as Ktor's Darwin engine makes them, was never captured) The correlation header per M4.1
  (c) in `sdk/ktor`; the Swift SDK's hooks skip what carries it. First
  tries the in-process mark ADR-0064 names; a working one goes to a new ADR.
  Accept: `./gradlew :sdk:ktor:allTests` and `swift test --package-path
  sdk/ios` — a request with the header yields no Swift capture, one
  without it still does.
- [x] **M4.8 `sdk/ktor` no-op and publication.** (`build/sdk-ktor-no-op-publication`; ADR-0072 supersedes ADR-0026. Deviations:
  `sdk/no-op` split into `sdk/core-no-op`, `sdk/okhttp-no-op`, `sdk/ktor-no-op` per ADR-0062 C.1 and became the
  unpublished check project; `sdk/ktor` itself was not published yet and joins the dry-run; the no-op iOS dumps M4.3
  deferred land here) The no-op M4.1 (a) picks
  joins the ABI comparison (ADR-0026); the artifacts join `make
  publish-dry-run` (ADR-0058).
  Accept: `./gradlew :sdk:no-op:check` and `make publish-dry-run` list the
  new artifacts.
- [x] **M4.8a Hermetic end to end (DEBT-0047, fired).** (`test/hermetic-end-to-end`; ADR-0074. Deviation: the KMP
  sample takes the same `target` parameter, for M4.10; the scripts also fail when a request leaves the target) `make e2e-android`
  and `make e2e-ios` start a local HTTP and WebSocket target next to the
  headless server; the samples take its address at launch (the emulator's
  `10.0.2.2`, the simulator's `127.0.0.1`) and keep the public URLs as the
  default for hand runs. M4.9 and M4.10 reuse it.
  Accept: `make e2e-android` and `make e2e-ios` pass and every URL in their
  server logs is the local target; DEBT-0047 `Paid`.
- [x] **M4.9 KMP sample app.** (`feat/kmp-sample-app`; ADR-0073: ADR-0062 D.1 proven on iOS by `CONFIGURATION`, on Android by
  substituting the no-ops in the app's release configurations. Deviations: the sample's Android modules need compileSdk 37,
  Compose Multiplatform 1.12 and Ktor's OkHttp 5.5 require it; `make kmp-sample` joins `make swift`;
  done before M4.8a, added while it ran, so the sample calls the public URLs and M4.8a takes `samples/kmp` too) `samples/kmp` per M4.1 (d): every call of
  the other samples (GET, POST, failure, WebSocket if M4.6 ships) through
  Ktor, a `runAll` launch path as ADR-0056 and ADR-0057, the release build
  without the SDK.
  Accept: the sample's Android and iOS debug builds run against `make
  server` and every call shows; its release builds contain no SDK names.
- [ ] **M4.10 KMP end to end.** `make e2e-kmp`: headless server, the KMP
  sample on an Android emulator and an iOS simulator with `runAll`, every
  envelope asserted, as `make e2e-android` and `make e2e-ios`; on iOS with
  the Swift SDK also running, each exchange once. Then the release gate
  job if M4.1 (d) allows.
  Accept: `make e2e-kmp` green on the Mac; a call removed from the run
  makes it fail, shown in the PR; the gate run green if the job is added.
- [ ] **M4.11 Docs sync.** README status, platforms and KMP usage; SDD 01
  and 03; `AGENTS.md` commands.
  Accept: `make docs-check`; every command the milestone added is in
  `AGENTS.md`.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
