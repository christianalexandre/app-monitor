---
id: ADR-0035
title: iOS SDK public API, package products and minimum iOS
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0035 — iOS SDK public API, package products and minimum iOS

## Context

PLAN-0003 (M2) ships the iOS SDK, a native Swift package (ADR-0003) that
captures all `URLSession` traffic (ADR-0004 layer 1). Its public API is a
"stop and ask" area (`AGENTS.md`). What it captures and masks is ADR-0036;
where its tests run is ADR-0037.

Already decided, not reopened here:

- Native Swift package, swift-protobuf, Network.framework, no Pulse
  (ADR-0003).
- One entry point per SDK, `AppMonitor.start()` with options; SDK code
  compiles to nothing in release, through a no-op with the same public API
  (guidelines 01).
- The shape of the Android API: explicit `start` / `stop`, an immutable
  options object with defaults — `enabled`, `host`, `port` (47474),
  `appName`, `maskedHeaders`, `maskedQueryParameters`, `maxBodyBytes`
  (1 MiB, at most `Protocol.MAX_BODY_BYTES`), `masking` — and "the iOS SDK
  follows the same shape in Swift idiom" (ADR-0017, ADR-0018, ADR-0034,
  `sdk/core/.../AppMonitorOptions.kt`).

Evidence, checked on 2026-10-23:

- Xcode 26.x (26.6 installed here, Swift 6.3.3, iOS 26.5 simulator runtime):
  deployment targets and simulators "iOS 15 or later"
  (<https://developer.apple.com/support/xcode/>).
- `URLSessionWebSocketTask`: iOS 13.0; `URLSessionConfiguration
  .protocolClasses`: iOS 7.0 (Apple documentation JSON,
  `developer.apple.com/tutorials/data/documentation/foundation/...`).
- swift-protobuf: latest tag `1.39.0`, Apache-2.0 with Runtime Library
  Exception, `swift-tools-version:6.2` (its `Package.swift` at that tag).
- SwiftPM gives every package target `SWIFT_ACTIVE_COMPILATION_CONDITIONS
  DEBUG` in its debug configuration, both for Xcode's build system
  (`Sources/SwiftBuildSupport/PackagePIFBuilder.swift:718`) and the older
  one (`Sources/XCBuildSupport/PIFBuilder.swift:349`) in
  `swiftlang/swift-package-manager` `main`. How an app's custom
  configuration (`Staging`) maps to a package's debug or release is not
  verified.
- The previous tool (`christianalexandre/app-network-monitor`) uses
  `AppNetworkMonitor.shared.start()`, a static library product, iOS 15, and
  asks the app to wrap both the `import` and the call in `#if DEBUG`
  (`README.md`). `start()` registers a `URLProtocol` and swizzles
  `URLSessionConfiguration.default`, `.ephemeral`, `init` and
  `URLSession.init(configuration:delegate:delegateQueue:)` to prepend it to
  `protocolClasses` (`NetworkInterceptor.swift`, `URLSession+Swizzling.swift`).

## Options

### A. Entry point

1. **`enum AppMonitor` with `static func start(_ options:
   AppMonitorOptions = .init())` and `static func stop()`**, callable from
   any thread, called by the app in `App.init` or
   `application(_:didFinishLaunchingWithOptions:)`. Mirrors the Kotlin
   `object AppMonitor` (ADR-0017 A); an `enum` cannot be instantiated, so
   there is no `shared` to pass around.
2. **`AppMonitor.shared.start()`** (the previous tool) — same behaviour, one
   more public symbol, and a singleton instance with no state the app needs.

Recommended: 1.

### B. Options

```swift
public struct AppMonitorOptions: Sendable {
    public var enabled: Bool = true          // false: start() does nothing
    public var host: String? = nil           // manual host, skips discovery
    public var port: Int = 47474             // only with host
    public var appName: String? = nil
    public var maskedHeaders: Set<String> = []
    public var maskedQueryParameters: Set<String> = []
    public var maxBodyBytes: Int = 1_048_576 // at most Protocol.MAX_BODY_BYTES
    public var masking: Bool = true
    public init(enabled: Bool = true, host: String? = nil, /* … all … */)
}
```

1. **`struct` with `var` members and a defaulted `init`** (above). A value
   type: `start` keeps its own copy, so the mutability Kotlin avoided
   (ADR-0017 B, option 3) cannot leak. A new option is a new defaulted
   parameter, source-compatible. An out-of-range `port` or `maxBodyBytes`
   stops the app with a `precondition` naming the value, as Kotlin's
   `require` throws.
2. **Same struct with `let` members** — no `var options = .init();
   options.host = …` style; every value goes through `init`.
3. **Builder or closure (`start { $0.host = … }`)** — one more public type,
   no gain over a value type.

Recommended: 1. Same names, defaults and limits as Android, member for
member, so one doc table serves both.

### C. How capture is turned on

1. **Automatic at `start()`**: every `URLSession` of the process is
   captured — `URLSession.shared`, sessions created by third-party SDKs,
   and sessions created before `start()` (ADR-0004 layer 1). Zero code per
   session. The mechanism (`URLProtocol` vs observing tasks and proxying
   delegates) is an implementation choice for M2.7 under an `Accepted` ADR,
   held to M2.7's "never alters the app's call".
2. **Explicit per configuration**:
   `AppMonitor.configure(_ configuration: URLSessionConfiguration)` — no
   swizzling, but misses `URLSession.shared` and every session a
   third-party SDK creates, which is the gap ADR-0004 exists to close.
3. **Automatic, with an opt-out option** (`captureURLSession: Bool`) — one
   more member; nothing asks for it yet.

Recommended: 1.

### D. Package products and the release no-op

1. **One product, `AppMonitor`; the implementation compiles only under
   `#if DEBUG`.** In release the same public `enum` and `struct` are empty
   stubs, so the app writes no `#if` and adds one dependency. The no-op
   check (M2.10) compares the public interface of the debug and release
   builds of the same module. Rests on SwiftPM's `DEBUG` condition (above);
   a custom configuration that maps to debug ships the SDK, which M2.10
   documents after checking it.
2. **Two products, `AppMonitor` and `AppMonitorNoOp`.** Same shape as
   Android (ADR-0026), but two modules need two names, so the app writes
   `#if DEBUG import AppMonitor #else import AppMonitorNoOp`, and Xcode
   links a package product into every configuration of a target: the app
   needs build settings to keep the SDK out of release.
3. **One product, the app wraps every call in `#if DEBUG`** (the previous
   tool); release relies on the linker dropping an unreferenced static
   library. No no-op at all — an exception to guidelines 01 that needs its
   own ADR, and every call site carries the `#if`.

Recommended: 1. PLAN-0003 M2.10 ("No-op product") then means "the release
build of the same product", and M2.12's release check looks for SDK symbols
other than the stubs.

### E. Minimum iOS and tools

1. **iOS 15, `swift-tools-version: 6.2`.** iOS 15 is the oldest version
   Xcode 26 deploys to and simulates; everything the SDK needs is older
   (WebSocket task 13, Network.framework 12, async `URLSession` 15). Tools
   6.2 is what swift-protobuf 1.39.0 requires.
2. **iOS 17** — `@Observable` and newer Network.framework APIs; the SDK uses
   neither, and a debug dependency should not raise an app's floor.

Recommended: 1. The sample app (M2.12) may target newer.

## Decision

The owner accepted every recommendation: A.1 (`enum AppMonitor`, static
`start`/`stop`), B.1 (`struct` with `var` members, Android's names, defaults
and limits), C.1 (every `URLSession` captured at `start()`), D.1 (one
`AppMonitor` product, implementation under `#if DEBUG`, empty stubs in
release) and E.1 (iOS 15, `swift-tools-version: 6.2`).

## Consequences

- A public API change touches the release stubs in the same PR, and the
  M2.10 check fails otherwise.
- Option D.1 changes the wording of PLAN-0003 M2.10 and M2.12 (no separate
  product); D.2 keeps it.
- C.1 makes swizzling part of the SDK; it runs only in debug builds (D) and
  only after `start()`.
