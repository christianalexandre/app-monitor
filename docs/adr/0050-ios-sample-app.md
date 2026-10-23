---
id: ADR-0050
title: iOS sample app from an XcodeGen spec, checked by a symbol scan of its release build
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0050 — iOS sample app from an XcodeGen spec, checked by a symbol scan of its release build

## Context

PLAN-0003 M2.12 asks for `samples/ios`: a SwiftUI app whose buttons make GET
JSON, POST, 404, timeout, upload, WebSocket echo and background download
calls, the SDK in debug and the stubs of the same product in release
(ADR-0035 D.1, ADR-0048), and the local network keys from ADR-0049's script.
Accept: both configurations build for the simulator, each button reaches
`make server`, and the release app holds no SDK symbols but the stubs.
Already decided: XcodeGen for Xcode projects (ADR-0047), public services for
the sample's calls (ADR-0030).

Evidence, checked on 2026-10-23 on this machine (Xcode 26.6, XcodeGen
2.46.0, iPhone 17 Pro simulator, iOS 26.5):

- A warm Debug plus Release build of the sample took 15 s; cold, 13 s and
  18 s.
- A Debug app links the SDK into `Sample.debug.dylib` (Xcode's debug dylib
  for previews); a Release app has one `Sample` binary.
- `nm` plus `swift-demangle` on the Release binary: the only names of the
  `AppMonitor` module are `AppMonitor`, `AppMonitorOptions` and their
  members. ~14,800 SwiftProtobuf symbols are there too (DEBT-0040).
- A leaked internal `struct` referenced from a release stub left no symbol:
  the optimizer inlined it, so a check on type descriptors alone passed.
  With every `#if DEBUG` of the SDK turned on, the Release binary held 37
  module names besides the stubs (`MonitorClient`, `URLSessionCapture`, …).
- `simctl` cannot tap buttons; `simctl launch <app> -runAll YES` puts
  `runAll` in the app's `UserDefaults` argument domain.

## Options

1. **XcodeGen spec `samples/ios/project.yml`, project ignored; a
   `scripts/ios_sample_check.sh` gate in `make swift`** builds both
   configurations, checks the keys, and fails when the Release binaries name
   anything of the module but the two stubs. Same tool and shape as the test
   host; the gate keeps the sample building as the SDK changes.
2. **Same spec, checked by hand in the PR only.** No time added to
   `make swift`, but nothing stops a sample that no longer builds.
3. **An XCUITest target that taps every button against an in-process
   server.** Proves the run without `make server`, but needs a UI test host
   and a desktop in the test, for a sample.

## Decision

Option 1. The sample targets iOS 15 like the SDK; `-runAll YES` at launch
runs every button in turn, so a run against a desktop needs no taps. The
release check matches every `AppMonitor.<name>` in the demangled symbols of
the app's binaries and requires the Debug app to name `MonitorClient`, so a
scan that sees nothing fails.

## Consequences

- `make swift` grows by about 15 s on a Mac with XcodeGen; CI is unchanged
  (ADR-0037).
- Code the optimizer inlines whole into a stub leaves no symbol and is not
  caught; the API dump (ADR-0048) and the `#if DEBUG` file rule still are.
- The sample's buttons need internet, as on Android (ADR-0030).
