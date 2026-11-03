---
id: DEBT-0120
title: Findings of the H5 milestone review that normal use does not hit
status: Open # Open | Paid | Dropped
date: 2026-11-03
trigger: "The next PR that touches `desktop/app/src/main/kotlin/appmonitor/desktop/app/Main.kt`, `sdk/ios/Sources/AppMonitor/Device.swift`, `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/DeviceId.kt`, `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Storage.kt`, `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/MonitorClient.kt`, `scripts/trailer_check.sh`, `sdk/core/src/androidMain/kotlin/appmonitor/sdk/core/LocalNetworkPermission.kt` or `sdk/core/src/jvmAndAndroidMain/kotlin/appmonitor/sdk/core/FirstSettledScreen.kt`"
paid_date:
---

# DEBT-0120 — Findings of the H5 milestone review that normal use does not hit

## What is missing

`code-review` at `medium` of PLAN-0015 (`e47d580..30dda93`) found these.
None of them is hit in normal use (PLAN-0015, *H5 milestone review*):

1. `clearAllKeepingDesktopId` (`Main.kt`): "Clear all data" deletes
   `desktop.id` and writes it back in a second step. A crash between the two
   gives the next launch a new id, and paired physical devices refuse it.
2. `FileDesktopPin` (`Device.swift`) and `DeviceId.kt`: the pin file is not
   excluded from backup, unlike the device id beside it. A restore onto a new
   phone brings the old pin back with a new device id.
3. Paid in PLAN-0016 H6.1: `Storage.close` gave `queue.enqueue(EndRun)` the full
   `timeout` after the retention join may have used it.
4. `MonitorClient.kt`, and `pump` and `poll` in `MonitorClient.swift`: a
   report waits for the next envelope, since an oversized drop never enters
   the buffer to wake the sender. An idle app's last oversized envelope is
   counted late, or never if the SDK stops first.
5. `trailer_check.sh`: with `pipefail`, a failing `git log` reads as grep
   finding nothing, so an unknown base passes the check having read no commit.
6. `LocalNetworkPermission.kt` and `FirstSettledScreen.kt`: a 5-line KDoc block
   each, against the 4-line limit of guideline 02.

## Why it was accepted

Items 1 and 3 need a crash or a stalled writer at shutdown; item 2 a restored
phone holding a debug build; item 4 an idle app whose last envelope is over
`MAX_FRAME_BYTES`; item 5 a base CI always fetches; item 6 changes no behavior.
PLAN-0016 (H6) sweeps the backlog before M10.

## Trigger

The next PR that touches one of the files above; PLAN-0016's sweep otherwise.

Fired in H6.1, on `Main.kt` and `Storage.kt`. Item 3 was paid there; the rest is
placed in PLAN-0016's tasks, each the PR that works in those files: item 1 in
H6.2, items 2 and 4 in H6.3 and H6.4, items 5 and 6 in H6.6.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
