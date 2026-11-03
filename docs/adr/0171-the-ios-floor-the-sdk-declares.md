---
id: ADR-0171
title: The iOS floor the SDK declares, against the runtimes it can be tested on
status: Accepted
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

Option 3, decided by the owner on 2026-11-03. Option 1 was tried: the
owner installed the iOS 15.0 runtime, and `simctl` lists it as
unavailable, "not supported on hosts after macOS 14.99.0", on this macOS
26.4. The floor stays iOS 15: the owner's own app (the H4 field test's)
targets iOS 15.0, and a floor of 16 would stop it adding the SDK.

- The README states the lowest iOS tested: 16.7 on a device (the H4
  field test), and 16.0 on the simulator: a fifth download finished on
  2026-11-03 and `SIM_OS=16.0 make swift-sim` is green (ADR-0170).
- DEBT-0111 stays open, its trigger a Mac on macOS 14 or earlier, or an
  iOS 15 device, running the suite.

## Consequences

- An app targeting iOS 15 can add the SDK, on a floor nothing here has run:
  what breaks only below the lowest iOS tested shows in that app first, as
  `URLProtocol.task` did on iOS 16 (DEBT-0111).
- H5.6's Accept on the declared floor cannot be met on macOS 26. The task
  closes with the suite running on the simulator (ADR-0170) and the gap
  stated in the README.
- Apple's support table lists iOS 15 for Xcode 26; the host's macOS decides,
  so `simctl list runtimes` is the evidence for what can run here.
