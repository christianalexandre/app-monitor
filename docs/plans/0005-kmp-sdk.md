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
- [ ] **M4.2 `sdk/core` lifecycle on iOS on paper.** SDD 04 states where
  `sdk/core` on Kotlin/Native differs from the JVM: Ktor engine, the JDK
  calls it replaces, threading, what `stop()` does in each state, app
  background and foreground; each case names the test that will drive it
  (M3's lesson).
  Accept: `make docs-check`; every SDD 04 state has its iOS exits and a
  named test.
- [ ] **M4.3 `sdk/core` on iOS targets.** The iOS targets M4.1 (a) picks in
  `appmonitor.kmp-sdk` (supersedes ADR-0023's "no iOS artifact"); transport,
  buffer and backoff build and run there per M4.2; ABI dumps updated for
  the new targets.
  Accept: `./gradlew :sdk:core:allTests` runs `iosSimulatorArm64Test` with
  M4.2's cases, not skipped; a live test gated by an environment variable
  reaches `make server` from the iOS simulator.
- [ ] **M4.4 `sdk/core` iOS discovery and device identity.** Manual host →
  Bonjour from Kotlin/Native → the simulator's `127.0.0.1`, the same
  contract as ADR-0024 and the Swift SDK (ADR-0042); the `Hello` an iOS KMP
  app sends (device id, model, OS, app name); the device id file shared
  with the Swift SDK, and two sessions with one (`device_id`, `app_id`)
  kept open by the desktop (ADR-0064 B.1).
  Accept: `./gradlew :sdk:core:allTests` — discovery with fake browsers on
  the iOS simulator; the live test finds `make server` by Bonjour.
- [ ] **M4.5 `sdk/ktor` HTTP capture.** The plugin per M4.1 (a, b) on JVM,
  Android and iOS: request, response, failure, bounded bodies (ADR-0020),
  masking with the shared vector `protocol/fixtures/masking.json`, capture
  layer `ADAPTER` (ADR-0038); SDD chapter for the module.
  Accept: `./gradlew :sdk:ktor:allTests` — the plugin against Ktor's
  `MockEngine` and the masking vector, on JVM and the iOS simulator.
- [ ] **M4.6 `sdk/ktor` WebSocket capture.** Frames both ways and the close,
  truncation as ADR-0028, if M4.1 (b) keeps it in scope.
  Accept: `./gradlew :sdk:ktor:allTests` — frames captured against an
  in-process echo server on JVM.
- [ ] **M4.7 Dedupe with the Swift SDK.** The correlation header per M4.1
  (c) in `sdk/ktor`; the Swift SDK's hooks skip what carries it. First
  tries the in-process mark ADR-0064 names; a working one goes to a new ADR.
  Accept: `./gradlew :sdk:ktor:allTests` and `swift test --package-path
  sdk/ios` — a request with the header yields no Swift capture, one
  without it still does.
- [ ] **M4.8 `sdk/ktor` no-op and publication.** The no-op M4.1 (a) picks
  joins the ABI comparison (ADR-0026); the artifacts join `make
  publish-dry-run` (ADR-0058).
  Accept: `./gradlew :sdk:no-op:check` and `make publish-dry-run` list the
  new artifacts.
- [ ] **M4.9 KMP sample app.** `samples/kmp` per M4.1 (d): every call of
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
