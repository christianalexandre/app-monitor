---
id: PLAN-0015
title: Release readiness — what publishing makes expensive
status: Draft # Draft | Active | Done | Abandoned
milestone: H5
started:
finished:
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

- [ ] **H5.1 A release gate that finishes, in time.** `samples-ios` and
  `e2e-ios` stop building gRPC-Core cold: SwiftPM's checkouts and the
  sample's derived data are cached across gate runs, keyed by
  `Package.resolved` and the Xcode version (DEBT-0080). The ruleset's
  required checks are compared with the workflow's job names by a script
  in `make scripts-test` (DEBT-0048). `pr-conventions.yml` checks a
  `release/*` → `main` PR the way M10 will open it (DEBT-0050).
  Accept: three `release-gate.yml` runs on `dev` in a row, all jobs green,
  `e2e-ios` under 35 minutes on a warm cache; the job times in the PR.
- [ ] **H5.2 A gate that fails only on a real failure.** Each test that
  failed once on CI is reproduced (repeated runs, a held port, a loaded
  runner) and fixed at its cause, never skipped: DEBT-0032, DEBT-0056,
  DEBT-0065, DEBT-0066, DEBT-0091, DEBT-0094, DEBT-0110, and locally
  DEBT-0017 (Spotless). The mDNS and loopback tests
  ignore point-to-point and tunnel interfaces, so a VPN no longer fails
  `make check` (DEBT-0036, extended with `LifecycleTest.bindsLoopbackOnly`
  in `desktop/mcp`).
  Accept: each test run 50 times in a row green locally, its command and
  count in the PR; `make check` green on the Mac with the VPN up.
- [ ] **H5.3 The protocol M10 freezes.** Before the first published SDK,
  the wire carries what a later version cannot add without breaking an
  old one: `Hello` sends `appName` (DEBT-0024); the desktop sees how many
  events a device dropped and why, oversized, evicted or lost with a
  socket (DEBT-0023); a frame the desktop rejects after the peer's close
  reaches the client as that rejection (DEBT-0014); a lagging collector
  no longer stops the server reading pongs, so a reachable device is not
  closed with 1011 (DEBT-0022). New fields are optional: an SDK without
  them still connects.
  Accept: `./gradlew :protocol:allTests :sdk:core:allTests
  :desktop:server:test`; fixtures regenerated and mirrored in
  `Samples.swift`, `make swift`; `make e2e-android` and `make e2e-ios`
  asserting the app name and a drop count of zero.
- [ ] **H5.4 A device trusts only the desktop its developer chose.** The
  SDK stops connecting to the first `_appmonitor._tcp` it finds
  (DEBT-0020). The ADR weighs pinning the first desktop approved against
  a pairing code; an option that adds a setup step or changes the public
  API without a standing owner decision is `Proposed`, and the iteration
  stops there.
  Accept: a second desktop on the LAN (`make server` on another port)
  is refused by an SDK paired with the first, on Android, KMP and iOS;
  `./gradlew :sdk:core:allTests`, `make swift`, `make e2e-android`,
  `make e2e-ios`.
- [ ] **H5.5 Release builds keep their guarantees.** The no-op check sees a
  public no-arg secondary constructor missing from a dump (DEBT-0026).
  The Gradle plugin rewrites `newWebSocket` only for an
  `appmonitor-okhttp` that has it, and fails the debug build with one
  line otherwise (DEBT-0099). The Kotlin an app needs to compile against
  the SDK is settled before it is published: lowered, or stated with its
  reason (DEBT-0108).
  Accept: `./gradlew :sdk:no-op:check` failing on a crafted constructor
  dropped from a no-op; `./gradlew -p gradle-plugin check
  :gradle-plugin:functionalTest` with a consumer forcing an older SDK.
- [ ] **H5.6 The SDKs on a physical iPhone.** The owner connects an iPhone
  to the Mac. The loop prepares what runs there: `make e2e-ios` and
  `make e2e-kmp-ios` take a device id, sign with the owner's free team, and
  assert Bonjour discovery and the local network prompt for the Swift SDK
  (DEBT-0041) and for `sdk/core`'s BSD sockets (DEBT-0052). With no
  device attached the task is `[!]`, waiting for the owner.
  Accept: both end to ends green on the iPhone, its model and iOS version
  in the PR.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
