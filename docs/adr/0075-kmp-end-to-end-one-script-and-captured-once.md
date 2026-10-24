---
id: ADR-0075
title: The KMP end to end is one script per platform argument, and every end to end asserts each exchange once
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0075 — The KMP end to end is one script per platform argument, and every end to end asserts each exchange once

## Context

PLAN-0005 M4.10: `make e2e-kmp` drives `samples/kmp` on an Android
emulator and an iPhone simulator with `runAll`, every envelope asserted as
`make e2e-android` and `make e2e-ios` do (ADR-0056, ADR-0057), against the
local target (ADR-0074); on iOS, with the Swift SDK also running, each
exchange shows once (ADR-0064). ADR-0065 C.1 already put two jobs in the
release gate, `e2e-kmp-android` on ubuntu and `e2e-kmp-ios` on macOS.

Already decided and not reopened: the samples' launch parameters, the
assertions on the headless server's log, the target's paths.

Evidence, runs on 2026-10-24 on an Apple Silicon Mac (emulator
`Medium_Phone`, iPhone 17 Pro simulator on iOS 26.5): both platforms pass;
the iOS server log has two `connected:` lines for
`dev.appmonitor.kmpsample` and one `RequestStarted` per call, all on the
Kotlin session; Ktor logs the WebSocket as `ws://<host>:<port>/ws`, where
the Swift SDK logs `http://`. With the 404 button removed from `App.kt`,
`make e2e-kmp-android` failed with
`missing: GET http://10.0.2.2:N/status/404: no RequestStarted`.

## Options

### A. Script layout

1. **Two more scripts**, `e2e_kmp_android.sh` and `e2e_kmp_ios.sh`, copies
   of the existing two with other app ids — four scripts repeating device
   pick, build and launch.
2. **One `scripts/e2e_kmp.sh android|ios`**, with the emulator and
   simulator pick moved into `scripts/e2e_lib.sh` for all three scripts;
   `make e2e-kmp-android`, `make e2e-kmp-ios` and `make e2e-kmp` (both) as
   the gate jobs need one platform each.

### B. "Each exchange once"

1. **Only on `e2e-kmp-ios`** — the one run with two capturing SDKs.
2. **In `assert_envelopes` for every end to end**: after every expectation
   arrived and a 3 s settle, fail when an expected request has more than one
   `RequestStarted`; `e2e-kmp-ios` also fails with fewer than two sessions
   of its app, since a missing Swift session would make "once" vacuous. A
   double capture is a bug on any platform (ADR-0003).

### C. iOS build

1. **`make kmp-sample`**, as `make e2e-ios` builds through
   `make ios-sample` — also the Android debug and release builds and the
   iOS Release, so the macOS gate job would need the Android SDK 37
   platform (ADR-0073) for an iOS run.
2. **The Debug app only**, `xcodebuild` on the picked simulator's UDID,
   for both iOS end to ends; Release and the name scans stay in
   `make ios-sample` and `make kmp-sample`, which `make swift` runs, and so
   the gate's `contract` job. This pays DEBT-0049's double build: `e2e-ios`
   built both configurations again on another runner.

## Decision

A.2, B.2 and C.2. The assertions of `e2e_lib.sh` get a device-free test,
`scripts/test_e2e_lib.sh`, in `make scripts-test`.

## Consequences

- A new end to end platform is a `case` in one script, not a new script.
- An SDK change that captures a request twice fails all four end to ends,
  not only the KMP one.
- The settle adds 3 s to each end to end; a duplicate arriving later than
  that is not caught.
- The release gate gains two jobs; run 40113961385 measured `e2e-kmp-android`
  at 3.6 min and `e2e-kmp-ios` at 22.0 min (ADR-0065 estimated ≈10–12);
  the wall time stays `contract`'s, 43.1 min in that run (DEBT-0057).
- Each end to end job still builds its Debug app, as `contract` does on
  another runner: sharing it would make the end to ends wait for
  `contract` and lengthen the gate.
