---
id: PLAN-0014
title: Plug and play setup
status: Active # Draft | Active | Done | Abandoned
milestone: H4
started: 2026-10-28
finished:
---

# PLAN-0014 — Plug and play setup (H4)

## Goal

A team adds App Monitor to an existing app with **the dependency and one
start call**: on Android and KMP the Gradle plugin and `AppMonitor.start`;
on iOS the Swift package and `AppMonitor.start`, plus one Run Script
pasted from the README for physical devices. No SDK code reaches a
production build, and a build that would ship it fails.

The first field test (a KMP app, Ktor on Android and iOS, configurations
`Dev` and `Prod`) touched ten files and showed no traffic: the Swift SDK
was compiled out in `Dev`, by Xcode's name rule (ADR-0155), the Android client stopped itself
on the app's cleartext policy, and the release swap, the local network
permission and a second Kotlin start on iOS were all written by hand.

## Tasks

- [ ] **H4.1 Android asks for its permission and stops blocking itself.**
  `AppMonitor.start(context)` asks for `ACCESS_LOCAL_NETWORK` on the first
  resumed Activity (Android 17+) through `ActivityLifecycleCallbacks`. The
  client no longer stops on the app's cleartext policy: CIO's sockets never
  obey it (ADR-0023). The README and `samples/android`, `samples/kmp` drop
  the debug network security config and `LocalNetwork.kt`. New ADR,
  superseding ADR-0017 E/F, ADR-0023's cleartext check and ADR-0132.
  Accept: `make e2e-android` and `make e2e-kmp-android` on an emulator,
  with the samples free of `LocalNetwork.kt`, their network security config limited to their own test target (ADR-0156); `./gradlew :sdk:core:allTests`.
- [ ] **H4.2 The Gradle plugin adds the SDK and swaps it for the no-ops
  (ADR-0093, from M10).** Applied to an app or a KMP module, it adds the
  real SDK to debuggable variants and to debug Apple frameworks, the
  no-ops elsewhere (`KOTLIN_FRAMEWORK_BUILD_TYPE`, then `CONFIGURATION`),
  and **fails the build** when a non-debuggable variant or a release
  framework resolves a real SDK artifact. `samples/kmp` and
  `samples/android` lose their hand-written swap.
  Accept: `./gradlew :gradle-plugin:functionalTest`. A consumer with only
  `plugins {}` and `AppMonitor.start` has the SDK in debug and none in
  release (`dexdump`). A consumer forcing the real SDK into release fails.
  `make kmp-sample` passes its release scan.
- [ ] **H4.3 KMP on iOS through the Swift SDK alone.** Ktor's Darwin engine
  runs on `URLSession`, and the Ktor plugin marks nothing until a Kotlin
  `AppMonitor.start` runs (ADR-0064). So an iOS app with the Swift SDK
  needs no Kotlin start and no `sdk/core` in `iosMain`. The README and
  `samples/kmp` drop both. New ADR.
  Accept: `make e2e-kmp-ios`, where each Ktor call shows once, from the
  Swift SDK's session only.
- [ ] **H4.4 The app leaves the iOS SDK out of release builds
  (ADR-0155).** The package keeps no `#if DEBUG`. The app starts it under
  its own `#if DEBUG` and sets `EXCLUDED_SOURCE_FILE_NAMES = AppMonitor*`
  on each configuration that ships. The Run Script, inline in the README,
  leaves an excluded configuration as is, fails an optimized one that
  links the SDK, and adds the keys otherwise. ADR-0048 and ADR-0049
  superseded. SDD 01, 03, 06, 07 updated.
  Accept: `scripts/test_add_local_network_keys.sh` (excluded patterns,
  optimized linked builds failing, the README block equal to the
  script); `make swift`; `make ios-sample` and `make kmp-sample` with no
  SDK name in the Release app; `make swift-sim`.
- [ ] **H4.5 Installation from the README alone.** The README's Usage
  becomes **Installation**: per platform, the dependency, the start call,
  then what is optional. Until M10, the steps to consume an unpublished
  SDK (`publishToMavenLocal`, the SwiftPM branch). The debug client logs
  once when what it needs is missing: no `NSBonjourServices` on a device,
  or the permission denied.
  Accept: fresh projects that follow only the README, an Android app
  (TestKit consumer) and an iOS app (XcodeGen, configurations `Dev` and
  `Prod`), build and send traffic in debug, and their release builds
  hold no SDK names; the step count per platform is listed in the PR.
- [ ] **H4.6 The iOS SDK without SwiftProtobuf.** The package pins
  `swift-protobuf` at `exact: 1.39.0`, so an app on another version cannot
  resolve both. Its runtime also stays in release builds (DEBT-0040). A
  minimal codec generated from `protocol/proto` replaces it. New ADR,
  changing ADR-0003 and ADR-0039.
  Accept: `make swift`, where `FixtureTests` decodes and re-encodes every
  fixture byte for byte; the root `Package.swift` with no
  `swift-protobuf`; `make ios-sample` with no `SwiftProtobuf` symbol in
  the Release app; DEBT-0040 paid.

The owner re-runs the field test in the same KMP app once H4.5 merges.
That is the plan's real check and not loop work: what it finds goes into
the Outcome.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
