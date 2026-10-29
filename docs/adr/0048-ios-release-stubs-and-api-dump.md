---
id: ADR-0048
title: iOS release stubs under #if DEBUG, checked against a committed interface dump
status: Superseded
date: 2026-10-23
superseded_by: ADR-0155
---

# ADR-0048 — iOS release stubs under #if DEBUG, checked against a committed interface dump

## Context

ADR-0035 D.1 ships one `AppMonitor` product: the implementation compiles only
under `#if DEBUG`, and the release build of the same module keeps the public
`AppMonitor` and `AppMonitorOptions` as stubs. PLAN-0003 M2.10 asks for a
check that fails when one build gains a public symbol. ADR-0039 asks that the
generated code and SwiftProtobuf stay out of the release build. ADR-0035 left
open how an app's custom configuration maps to the package's `DEBUG`.

Evidence, checked on 2026-10-23 on this machine (Swift 6.3.3, Xcode 26.6,
XcodeGen):

- `swiftc -typecheck -enable-library-evolution -emit-module-interface-path`
  writes the module's public interface in 1.5 s, with default argument values
  (`port: Swift.Int = 47474`), which the Kotlin dumps cannot see (ADR-0026).
  The compiler's own verification of that interface fails because the
  `AppMonitor` enum shadows its module (swiftlang/swift#56573), so the check
  turns it off (`-no-verify-emitted-module-interface`).
- The interfaces of the debug and release builds differ only in their
  `import` lines once every implementation file is under `#if DEBUG`.
- The release build typechecks with no SwiftProtobuf module on the search
  path, so nothing compiled in release imports it.
- A macOS command-line tool depending on the local package, built by
  `xcodebuild` in configurations `Debug`, `Release`, `Staging` (release
  settings), `StagingDebug` and `QA` (both debug settings): `MonitorHolder`
  symbols are in `Debug` and `StagingDebug` only. The package gets `DEBUG`
  when the app's configuration name contains `Debug`, not from its settings.
- In every configuration without the SDK, the binary still holds ~14,800
  SwiftProtobuf symbols (a 3.2 MB binary for two calls), with
  `DEAD_CODE_STRIPPING = YES` too: Xcode links each package target as an
  object file, and nothing in the `AppMonitor` module refers to it.
- swift-format's `indentConditionalCompilationBlocks` (default `true`) would
  indent every line of a file wrapped in `#if DEBUG`.

## Options

1. **One declaration of each public type, bodies under `#if DEBUG`; every
   other source file wrapped in `#if DEBUG`; both builds' interfaces compared
   with a committed dump `sdk/ios/api/AppMonitor.swiftinterface`.** The
   public API is written once, so the stubs cannot drift by accident; a
   public member added under `#if DEBUG` or `#if !DEBUG` fails the check, and
   any public API change shows as a dump diff in the PR, as on Android.
2. **A separate `ReleaseStubs.swift` under `#if !DEBUG` with its own copy of
   the public types**, compared debug vs release. Mirrors `sdk/no-op`, but two
   copies of every doc comment and default to keep equal, and no dump to
   review.
3. **`swift-api-digester -diagnose-sdk` between the two builds.** Reports
   breaking changes, not additions, and needs both builds as modules.

## Decision

Option 1, in `scripts/swift_api_check.sh`, run by `make swift`. It also fails
when a source other than `AppMonitor.swift` and `ProtocolConstants.swift`
does not open with `#if DEBUG` after its leading `//` lines and end with
`#endif`; the release stubs keep
`AppMonitorOptions`' range checks, as `sdk/no-op` does. `make swift-proto`
wraps the generated files the same way, and `.swift-format` sets
`indentConditionalCompilationBlocks: false`. `scripts/test_swift_api_check.sh`
proves the check fails on a debug-only member, a release-only member and an
unwrapped file.

## Consequences

- A public API change runs `scripts/swift_api_check.sh --update` and commits
  the dump; `make swift` fails otherwise. `make swift` grows by ~10 s (check
  and its tests); CI is unchanged (ADR-0037).
- An app's configuration whose name lacks `Debug` gets the stubs even with
  debug settings: the SDK is off, never on by mistake in a release.
- The release app still links SwiftProtobuf (DEBT-0040).
- The interface is the macOS build's; iOS-only public API would need an iOS
  build in the check.
