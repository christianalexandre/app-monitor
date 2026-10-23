---
id: ADR-0047
title: iOS simulator tests run in an XcodeGen test host app
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0047 — iOS simulator tests run in an XcodeGen test host app

## Context

PLAN-0003 M2.9 accepts on "an `xcodebuild test` on the iPhone simulator".
Evidence, 2026-10-23, Xcode 26.6, iOS 26.5 simulator:

- `xcodebuild test` on the `sdk/ios` package scheme for the simulator does
  not build: the test target uses exit tests and iOS 16 APIs, macOS-only or
  above the package's iOS 15 floor.
- Package tests on the simulator run in a bare `xctest` process; a
  background session there fails every task ("remote session is
  unavailable"). The same test hosted in an app passes.
- A SwiftPM package cannot declare an app target, so a hosted test needs an
  Xcode project. XcodeGen 2.46.0 (MIT, `brew install xcodegen`) generates
  one from a YAML spec; the generated project must sit beside the spec for
  its local package path to resolve.
- A cold `scripts/swift_sim_check.sh` took 9.7 s, warm about 14 s with
  simulator boot jitter, on this Mac with the simulator already booted.

## Options

1. **Commit a hand-made `.xcodeproj`** — no extra tool, but an opaque
   `project.pbxproj` in every review, edited only through Xcode.
2. **XcodeGen spec, project generated and ignored** — a 30-line reviewable
   spec; one more tool on the developer's Mac.
3. **Make the whole package test target compile for iOS** — no app, but a
   bare `xctest` process cannot run background sessions, so it does not
   answer M2.9.

## Decision

Option 2. `sdk/ios/TestHost/project.yml` defines the `TestHost` app and the
hosted `BackgroundTests` bundle, which links the `AppMonitor` package and
shares `TestHTTPServer.swift` with the package tests.
`scripts/swift_sim_check.sh` (`make swift-sim`) generates the project and
runs it on the first available iPhone simulator, or `SIM_DEVICE`. `make
swift` runs it after `swift test`, and prints `swift-sim: SKIPPED` when
`xcodegen` is missing.

## Consequences

- Only tests that need iOS or an app go in the test host; the rest stay in
  `swift test`.
- Local `make check` grows by about 10–15 s on a Mac with XcodeGen; CI is
  unchanged (ADR-0037).
- M2.12's sample app can use the same tool.
