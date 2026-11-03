---
id: DEBT-0120
title: Findings of the H5 milestone review that normal use does not hit
status: Open # Open | Paid | Dropped
date: 2026-11-03
trigger: "The next PR that touches `scripts/trailer_check.sh`, `sdk/core/src/androidMain/kotlin/appmonitor/sdk/core/LocalNetworkPermission.kt` or `sdk/core/src/jvmAndAndroidMain/kotlin/appmonitor/sdk/core/FirstSettledScreen.kt`"
paid_date:
---

# DEBT-0120 — Findings of the H5 milestone review that normal use does not hit

## What is missing

`code-review` at `medium` of PLAN-0015 (`e47d580..30dda93`) found these.
None of them is hit in normal use (PLAN-0015, *H5 milestone review*):

1. Paid in PLAN-0016 H6.2d: `clearAllKeepingDesktopId` (`Main.kt`) deleted
   `desktop.id` and wrote it back in a second step; now `Storage.clearAll`'s
   transaction never deletes it.
2. `FileDesktopPin` (`Device.swift`): the pin file is not excluded from backup,
   unlike the device id beside it. A restore onto a new phone brings the old pin
   back with a new device id. Paid in `sdk/core` by PLAN-0016 H6.3d: `iosDesktopPin`
   excludes it when pinned; a pin written before stays in backups, and no release
   has shipped one. Android's is in `noBackupFilesDir`.
3. Paid in PLAN-0016 H6.1: `Storage.close` gave `queue.enqueue(EndRun)` the full
   `timeout` after the retention join may have used it.
4. `pump` and `poll` in `MonitorClient.swift`: a report waits for the next
   envelope, since an oversized drop never enters the buffer to wake the sender.
   An idle app's last oversized envelope is counted late, or never if the SDK
   stops first. Paid in `MonitorClient.kt` by PLAN-0016 H6.3d: the drop wakes the
   sender (`anIdleSessionReportsAnOversizedEnvelopeAtOnce`).
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

Fired in H6.2d, on `Main.kt` and `Storage.kt`. Item 1 was paid there, and with item
3 paid too, neither file holds an item left: both leave the trigger.

Fired in H6.3d, on `DeviceId.kt` and `MonitorClient.kt`. Items 2 and 4 were paid there
for `sdk/core`, so both files leave the trigger and `MonitorClient.swift` joins it; the
Swift side is H6.4's.

Fired in H6.4a, on `Device.swift` and `MonitorClient.swift`. Items 2 and 4 were paid there for the
Swift SDK (`aPinnedDesktopIsExcludedFromBackup`, `anIdleSessionReportsAnOversizedEnvelopeAtOnce`), and
both files leave the trigger.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
