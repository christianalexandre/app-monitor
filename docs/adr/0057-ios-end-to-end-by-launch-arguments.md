---
id: ADR-0057
title: iOS end to end driven by launch arguments, sharing the Android assertions
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0057 — iOS end to end driven by launch arguments, sharing the Android assertions

## Context

PLAN-0004 M3.7 asks for `make e2e-ios`: the headless server, the Debug
sample of `make ios-sample` on a booted simulator launched with
`-runAll YES`, the server log asserted as `make e2e-android` does
(ADR-0056). M3.8 runs the same target on the gate's macOS runner, where no
simulator is booted.

Already decided and not reopened: the sample runs every button on
`-runAll YES` (ADR-0050); the headless server admits every device and
prints one line per envelope; the SDK on the simulator falls back to
`127.0.0.1`, but on port 47474 (`Discovery.swift`, ADR-0002), while the
script needs a free port so it never collides with a running desktop.

Evidence, from runs on 2026-10-23 on an Apple Silicon Mac, Xcode 26,
iPhone 17 Pro simulator (iOS 26.5): `xcrun simctl launch <udid> <bundle>
-host 127.0.0.1 -port N -runAll YES` puts the three values in the app's
`UserDefaults` argument domain; the server logged all seven calls, the
background download included; the WebSocket's `RequestStarted` URL is
`https://echo.websocket.org`, not `wss://`.

## Options

1. **XCUITest target pressing the buttons** — a UI test bundle in
   `project.yml`, run by `xcodebuild test`; the server would still run
   beside it, and the port has to reach the app anyway.
2. **Launch arguments read by the sample, assertions copied from
   `e2e_android.sh`** — no SDK change; two copies of the log parser to keep
   in step.
3. **Launch arguments, assertions in one sourced file** — as 2, with the
   server start and the per-envelope check moved to `scripts/e2e_lib.sh`,
   sourced by both scripts.

## Decision

Option 3. The sample reads `-host` and `-port` from `UserDefaults` and
passes them to `AppMonitorOptions`. `scripts/e2e_ios.sh` picks the booted
iPhone simulator (`SIM_DEVICE` filters by name) or boots the first
available one, builds through `scripts/ios_sample_check.sh`, starts the
server on port 0, installs and launches the Debug app with the three
arguments, and waits up to `E2E_TIMEOUT` seconds for each expected
envelope. The simulator log of the `Sample` process since the start is
written to `build/e2e-ios/simulator.log`. Not part of `make check`.

## Consequences

- A sample button added or renamed on either platform must be added to that
  script's expected list.
- `e2e_android.sh` changes shape (same behaviour); a change to the server's
  log format now breaks both end to ends at once, in one file.
- The run depends on `httpbin.org` and `echo.websocket.org` (DEBT-0047).
- It proves the simulator only; a physical iPhone is DEBT-0041.
