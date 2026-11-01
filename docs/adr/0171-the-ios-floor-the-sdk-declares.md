---
id: ADR-0171
title: The iOS floor the SDK declares, against the runtimes it can be tested on
status: Proposed
date: 2026-10-31
superseded_by:
---

# ADR-0171 — The iOS floor the SDK declares, against the runtimes it can be tested on

## Context

DEBT-0111 (PLAN-0015 H5.6): the SDK's tests, mocks and breakpoints
included, run on the lowest iOS `Package.swift` declares, or that floor is
raised to what runs. ADR-0035 E.1 chose iOS 15 because it is the oldest
version Xcode 26 deploys to and simulates; the samples (`samples/ios`,
`samples/kmp/iosApp`) target 15.0 too. Changing the floor changes what an
app developer can build against: a public API decision (`AGENTS.md`, stop
and ask 2) that no standing owner decision covers.

Evidence, 2026-10-31, Xcode 26.6 on macOS 26, Apple silicon:

- Xcode 26 simulates iOS 15 and later (developer.apple.com/support/xcode;
  DTS answer in developer.apple.com/forums/thread/814284).
- `xcodebuild -downloadPlatform iOS -buildVersion <v>` answers "not
  available for download" for 15.0, 15.2, 15.4, 15.5, 15.6 and builds
  19A339 and 19F70, and serves 16.0 (build 20A360). The runtime images on
  `download.developer.apple.com` redirect to `/unauthorized/` without an
  Apple ID session. The loop has no Apple ID.
- The iOS 16.0 image is 6.28 GB; on the loop's network its download failed
  four times (timed out at 72 %, connection lost, then offline), so
  `SIM_OS=16.0 make swift-sim` (ADR-0170) has not run either.
- The H4 field test ran the SDK on an iPhone 8, iOS 16.7.16, by hand.

## Options

1. **Keep iOS 15; the owner installs an iOS 15 runtime once.** Xcode >
   Settings > Components, or the image from developer.apple.com/download/all
   with `xcodebuild -importPlatform <dmg>`; then `SIM_OS=15.x make
   swift-sim` on the loop's Mac pays DEBT-0111. No app changes anything;
   one manual step for the owner, on each machine that runs the loop.
2. **Raise the floor to iOS 16** in both `Package.swift`, the samples and
   `sdk/core`'s iOS docs. The lowest tested iOS is the declared one, and the
   loop can install it alone; an app whose debug build targets iOS 15 can no
   longer add the SDK until it raises its own target.
3. **Keep iOS 15, state iOS 16 as the lowest tested** in the README. No one
   changes anything, but the declared floor stays unproven, which is what
   DEBT-0111 was opened against.

Recommended: 1, if the owner can install the runtime; else 2. A debug-only
SDK should not raise an app's floor (ADR-0035 E), and option 1 costs one
download.

## Decision

Empty while `Proposed`.

## Consequences

- Option 1: H5.6 closes once `SIM_OS=15.x make swift-sim` is green, its
  version in the PR.
- Option 2: an API change to both SDKs' declared platforms, in the README's
  setup and the `api/` dumps if they record it.
