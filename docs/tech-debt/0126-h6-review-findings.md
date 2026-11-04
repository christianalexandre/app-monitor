---
id: DEBT-0126
title: Findings of the H6 milestone review that normal use does not hit
status: Open # Open | Paid | Dropped
date: 2026-11-04
trigger: "The next PR that touches a file its items name: `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/DataDirectoryLock.kt`, `scripts/e2e_android.sh`"
paid_date:
---

# DEBT-0126 — Findings of the H6 milestone review that normal use does not hit

## What is missing

`code-review` at `medium` of PLAN-0016 (`e47d580..2b6cb70`) found these, read in the
code and not reproduced (PLAN-0016, *H6 milestone review*):

1. to 3., the proxy's: paid in PLAN-0017's H7.1 (`TunnelTls`, `WebSocketCapture.kt`, `ClientHandler.kt`).
4. `DataDirectoryLock.kt`: a refused lock inside the same JVM closes a second channel on
   the file, and on POSIX that drops the process's lock on it. `openServices` opens the
   store once per process, so only a new second caller would hit it.
5. `scripts/e2e_android.sh`: `adb shell dumpsys … | grep -q` under `pipefail` can fail on
   adb's broken pipe, repeating until `rotate` gives up; `accelerometer_rotation` is left
   at 0 on the developer's emulator.
6. KDocs over the 4 lines of guideline 02, in `desktop/`, `protocol/` and `tools/`:
   four in `Storage.kt`, one each in `ServiceSwitch.kt`, `TrafficViewModel.kt`,
   `Capture.kt`, `ProxyRecording.kt`, `ProxyServer.kt`,
   `LanAddresses.kt`, `ServerConfig.kt`, `LiveTraffic.kt`, `Search.kt`,
   `DesktopSelector.kt` and `tools/fake-client`'s `Discovery.kt`.

## Why it was accepted

None is hit in normal use, per the rule of ADR-0094: 4 needs a second
store in one process; 5 is a test script and 6 has no behavior.

## Trigger

The next PR that touches `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/DataDirectoryLock.kt` or
`scripts/e2e_android.sh`, for that file's item; item 6 goes with whichever of its files
such a PR touches.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
