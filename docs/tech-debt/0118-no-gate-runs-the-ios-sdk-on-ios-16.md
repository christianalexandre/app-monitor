---
id: DEBT-0118
title: No gate runs the iOS SDK on iOS 16
status: Open # Open | Paid | Dropped
date: 2026-11-03
trigger: M10's first tagged release, or the next PR that changes `sdk/ios/Sources/AppMonitor/Masking.swift`, `sdk/ios/Sources/AppMonitor/Mocks.swift` or `sdk/ios/Sources/AppMonitor/Breakpoints.swift`
paid_date:
---

# DEBT-0118 — No gate runs the iOS SDK on iOS 16

## What is missing

`SIM_OS=16.0 make swift-sim` is run by hand (ADR-0170). `make swift-sim` and the release gate use
the newest runtime installed, so what breaks only on an old iOS goes unseen, as the
crash of #321 and the masking gap of PLAN-0015 H5.6 did.

## Why it was accepted

The runtime is a 6.28 GB download, which failed four times of five on the loop's network, and a
release gate job for it would add that to each run. No failure has yet come from a PR after the
suite first ran there.

## Trigger

M10's first tagged release, or the next PR that changes `Masking.swift`, `Mocks.swift` or
`Breakpoints.swift`. For a PR, run `SIM_OS=16.0 make swift-sim` and say so: on that runtime a
second install of `TestHost` fails ("Could not hardlink copy"), so first shut down and
`xcrun simctl erase` the simulator. For the release, decide whether the gate gets a job.

Fired in PLAN-0016 H6.6, on a comment in `Masking.swift`. `SIM_OS=16.0 make swift-sim` failed on `origin/dev`
too: `aSocketDroppedAfterTheUpgradeFails` (#362) pinned `NSPOSIXErrorDomain 54`, and iOS 16 reports 57. The
test now takes both, and the suite is green there (183 and 46 tests). The trigger stays: this is the failure it
exists to find, on a PR that changed none of those files.

## How it was paid

> Filled when closing.
