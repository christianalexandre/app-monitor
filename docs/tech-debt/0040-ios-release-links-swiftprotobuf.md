---
id: DEBT-0040
title: An iOS release build still links SwiftProtobuf
status: Paid
date: 2026-10-23
trigger: M10's first SwiftPM tag, or an app reports the SDK's size in a release build
paid_date: 2026-10-29
---

# DEBT-0040 — An iOS release build still links SwiftProtobuf

## What is missing

A release build excludes all of `AppMonitor` with `EXCLUDED_SOURCE_FILE_NAMES =
AppMonitor*` (ADR-0155, which replaced ADR-0048's stubs), but an app that
depends on the package still links all of SwiftProtobuf in release: ~14,800 symbols, ~3 MB in a macOS probe tool, with
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

ADR-0155 option 4 (a `.dynamic` product, measured at 0 SwiftProtobuf
symbols) is a way out that does not replace swift-protobuf, at the cost of
an *Embed & Sign* step.

## How it was paid

By ADR-0160: `scripts/swift_proto_gen.py` writes the protocol types with
protoc-gen-swift's names, on a hand-written `ProtoWire.swift`, and
`AppMonitor` no longer depends on swift-protobuf. `FixtureTests` still
decodes and re-encodes every fixture byte for byte. `make ios-sample` fails
when the sample's Debug or Release app holds a `SwiftProtobuf` symbol; it
holds none. The gRPC adapter keeps SwiftProtobuf, `from: "1.28.0"`, for the
app's own messages. What is left: a schema feature the generator lacks
stops `make swift-proto` until it writes it.
