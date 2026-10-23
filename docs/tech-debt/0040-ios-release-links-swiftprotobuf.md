---
id: DEBT-0040
title: An iOS release build still links SwiftProtobuf
status: Open
date: 2026-10-23
trigger: M2.12 checks the release sample's symbols, or an app reports the SDK's size in a release build
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

M2.12 checks the release sample's symbols ("no SDK symbols other than the
stubs"), or an app reports the SDK's size in a release build.

## How it was paid

> Filled when closing.
