---
id: ADR-0056
title: Android end to end driven by launch extras, asserted on the headless server log
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0056 — Android end to end driven by launch extras, asserted on the headless server log

## Context

PLAN-0004 M3.5 asks for `make e2e-android`: the headless server on a free
port, the sample's debug APK on a booted emulator, every sample call run
without a hand, the server log asserted per envelope. M3.6 runs the same
target in the gate, so it must work from a shell with only `adb`.

Already decided and not reopened: the sample starts the SDK in
`Application.onCreate` (ADR-0030); the headless server admits every device
(`DeviceApproval.ApproveAll`) and prints one line per envelope
(`desktop/server/.../Main.kt`); the SDK falls back to `10.0.2.2` on the
emulator, but always on port 47474 (`Discovery.kt`, ADR-0002). The iOS
sample already runs every button on `-runAll YES` (ADR-0050).

Evidence, from runs on 2026-10-23 on an Apple Silicon Mac, emulator
`sdk_gphone16k_arm64` (API 36): the server started with `--port 0` prints
`Listening on port N`; `am start` on an activity that is already on top
answers result code 3 and hands the extras to `onNewIntent`, which happened
once because systemui relaunched the app after `am force-stop`.

## Options

1. **Instrumented test (`connectedAndroidTest`) with UI Automator** —
   presses the real buttons. Needs a test APK, an `androidTest` source set
   and a Gradle device connection; the server would still have to run
   beside Gradle, and asserting its log from inside the device is awkward.
2. **`adb shell input tap` from `uiautomator dump` coordinates** — no app
   change, but breaks on every layout change or screen size, and still needs
   the port.
3. **Launch extras read by the sample** — `--ez runAll true` runs every
   button in turn (as iOS `-runAll`); `--es host H --ei port N` restarts the
   SDK with that manual host, so the free port reaches it. A bash script
   drives `adb` and greps the server log. Small sample change, no SDK change.

## Decision

Option 3. `scripts/e2e_android.sh` builds the debug APK and the server's
`installDist`, starts the server on port 0, grants `ACCESS_LOCAL_NETWORK`
on API 37+, launches with `am start -S --activity-clear-task` and the
extras, then waits up to `E2E_TIMEOUT` seconds until, for each expected
call, the log has its `RequestStarted` (method and URL), the matching
terminal envelope by request id, and for the WebSocket the sent text, the
received text and the sent close frame. Status codes and bodies are not
asserted. It is not part of `make check`.

## Consequences

- A sample button added or renamed must be added to the script's expected
  list; a call missing from the run fails with one line per gap.
- The sample's calls go to `httpbin.org` and `echo.websocket.org`, so the
  test fails when they are down (DEBT-0047).
- The extras are in the sample only; the SDK public API is unchanged.
