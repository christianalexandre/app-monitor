---
id: DEBT-0040
title: An iOS release build still links SwiftProtobuf
status: Open
date: 2026-10-23
trigger: M10's first SwiftPM tag, or an app reports the SDK's size in a release build
paid_date:
---

# DEBT-0040 — An iOS release build still links SwiftProtobuf

## What is missing

The release build of `AppMonitor` is stubs with no SwiftProtobuf import
(ADR-0048), but an app that depends on the package still links all of
SwiftProtobuf in release: ~14,800 symbols, ~3 MB in a macOS probe tool, with
dead stripping on. `nm` on a release app shows `SwiftProtobuf` symbols.

## Why it was accepted

SwiftPM cannot make a dependency conditional on the build configuration, and
Xcode links every package target the app depends on. Leaving it out means
dropping swift-protobuf for hand-written encoding of the few messages the SDK
sends, a change to ADR-0003 and ADR-0039 bigger than M2.10's stubs.

## Trigger

M10's first SwiftPM tag (the first time an app links a published
`AppMonitor`), or an app reports the SDK's size in a release build.

The first trigger, M2.12 checking the release sample's symbols, fired in
#107: `scripts/ios_sample_check.sh` checks `AppMonitor` names only and
ADR-0050 records the SwiftProtobuf symbols. PLAN-0003's first milestone
review found it open and moved the trigger to M3, where publication is
designed.

The second trigger, M3.10's publication dry-run, fired: the release build
of a SwiftPM consumer of the root `Package.swift` links 3,636,088 bytes
with 14,767 `SwiftProtobuf` symbols, against 5,967,920 bytes and 18,095 in
debug (`make publish-dry-run`, ADR-0058). Paying it is still replacing
swift-protobuf, so the trigger moved to the first tag apps can resolve.

## How it was paid

> Filled when closing.
