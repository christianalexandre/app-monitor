---
id: ADR-0170
title: The iOS SDK's package suite runs on the simulator too, on the iOS SIM_OS picks
status: Accepted
date: 2026-10-31
superseded_by:
---

# ADR-0170 — The iOS SDK's package suite runs on the simulator too, on the iOS SIM_OS picks

## Context

PLAN-0015 H5.6 (DEBT-0111): the SDK declares `.iOS(.v15)`, but `swift test`
runs on the macOS host and `make swift-sim` ran only the hosted
`BackgroundTests` (ADR-0047) on the one installed runtime, iOS 26.5. The
H4 field test found what that hides: on an iPhone 8 with iOS 16.7, every
mocked call crashed on `URLProtocol.task`. Mocks and breakpoints are
tested only in the package suite, so no simulator ever ran them.

Evidence, 2026-10-31, Xcode 26.6, iPhone 17 Pro simulator, iOS 26.5:

- `xcodebuild test -scheme AppMonitor-Package` for the simulator did not
  build, as ADR-0047 found: three `Task.sleep(for:)` calls need iOS 16, and
  Swift Testing's exit tests are "not available on this platform" on iOS.
  With the sleeps in nanoseconds and the three exit tests under
  `#if os(macOS)`, it builds and 157 tests in 23 suites pass on the
  simulator, mocks and breakpoints included; the background session tests
  are not in this suite, they stay in the test host.
- Xcode 26 runs simulator runtimes from iOS 15 on (Apple's Xcode support
  table, developer.apple.com/support/xcode; DTS answer in
  developer.apple.com/forums/thread/814284). `xcodebuild -downloadPlatform
  iOS -buildVersion` serves 16.0 (build 20A360, 6.28 GB) and answers "not
  available for download" for 15.0, 15.2, 15.4, 15.5, 15.6 and builds
  19A339 and 19F70; an iOS 15 runtime image is behind an Apple ID on
  developer.apple.com.
- A cold `make swift-sim` with the package suite took 16 min, most of it
  building grpc-swift for the simulator; `make check` does not run it
  (ADR-0076).

## Options

1. **Hosted tests only, as before** — no change; mocks and breakpoints keep
   never running on iOS.
2. **Move the mock and breakpoint tests into the test host** — runs them on
   iOS, but splits one suite in two projects and leaves the rest of the
   package untested there.
3. **Run the whole package suite on the simulator before the hosted tests,
   and let `SIM_OS` pick the runtime** — one more `xcodebuild test`, the
   suite compiled for the iOS it ships to; the exit tests stay macOS only.

## Decision

Option 3. `scripts/swift_sim_check.sh` runs the package's
`AppMonitor-Package` scheme, then the test host, on the same destination;
`SIM_OS` (e.g. `16.0`) adds `OS=` to it, and the default device is the
first iPhone on that runtime (`ios_lib.sh`). ADR-0047's hosted tests are
unchanged.

## Consequences

- Test code under `sdk/ios/Tests` must build for the package's iOS floor:
  an API above it fails `make swift-sim`, not `make swift`.
- `make swift-sim` is slower, by the package build for the simulator
  (cold, about 15 min); `make check` and CI are unchanged.
- Which iOS floor the SDK declares, given the runtimes the loop can
  install, is ADR-0171.
