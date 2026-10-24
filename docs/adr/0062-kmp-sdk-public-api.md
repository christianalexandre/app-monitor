---
id: ADR-0062
title: KMP SDK public API, artifacts, no-op and iOS targets
status: Proposed
date: 2026-10-24
superseded_by:
---

# ADR-0062 — KMP SDK public API, artifacts, no-op and iOS targets

## Context

PLAN-0005 M4.1 (a). M4 ships `sdk/ktor`, a Ktor client plugin, and builds
`sdk/core` for iOS so a Kotlin Multiplatform app shows its Ktor traffic from
Android and iOS. What a KMP developer types is a "stop and ask" area
(`AGENTS.md`, public API of an SDK). What the plugin captures and masks is
ADR-0063; the dedupe with the Swift SDK is ADR-0064; the sample is ADR-0065.

Already decided, not reopened here:

- `object AppMonitor` with `start(context, options)` / `stop()` on Android,
  immutable `AppMonitorOptions` with defaults in `commonMain` (`enabled`,
  `host`, `port`, `appName`, `maskedHeaders`, `maskedQueryParameters`,
  `maxBodyBytes`, `masking`), adapters configured from the running
  monitor's options through `MonitorBridge` (ADR-0017, ADR-0018, ADR-0025,
  ADR-0034; `sdk/core/api/android/core.api`).
- Swift apps use the Swift SDK; KMP apps on iOS use the Ktor plugin
  (ADR-0003).
- Maven group `io.github.christianalexandre`, artifacts
  `appmonitor-<name>`, one `X.Y.Z` tag for every SDK (ADR-0054).
- Release builds link a no-op with the same public API, checked against
  committed ABI dumps (ADR-0026); today one `appmonitor-no-op` mirrors
  `sdk/core` and `sdk/android-okhttp` and depends on OkHttp
  (`sdk/no-op/build.gradle.kts`).
- `appmonitor.kmp-sdk` builds JVM and Android only (ADR-0023); M4.3
  supersedes that part.

Evidence, checked on 2026-10-24:

- Ktor: latest release 3.6.0 (Maven Central
  `io/ktor/ktor-client-core/maven-metadata.xml`); the catalog already pins
  3.6.0 and `sdk/core` depends on `ktor-client-core` and `ktor-client-cio`
  for its transport (`gradle/libs.versions.toml`), so every app that adds
  the Android SDK in debug already resolves Ktor ≥ 3.6.0 there. Ktor 3
  moved its I/O to kotlinx-io; "if your project interacts with Ktor's
  low-level I/O APIs, you may need to update your code"
  (<https://ktor.io/docs/migrating-3.html>). Ktor 2.x is not tested.
- Ktor's own capture plugin, `Logging`, is a `ClientPlugin<LoggingConfig>`
  built with `createClientPlugin` and installed with `install(Logging) { }`
  (Ktor 3.6.0 `ktor-client-logging/.../Logging.kt:126`).
- Kotlin/Native iOS tiers: tier 1 `iosSimulatorArm64` and `iosArm64`
  (iOS 15.0+); `iosX64` is tier 3, "not guaranteed to be tested on CI"
  (<https://kotlinlang.org/docs/native-target-support.html>).
- iOS frameworks: `embedAndSignAppleFrameworkForXcode` builds the debug or
  release framework from the Xcode configuration; a custom configuration
  sets `KOTLIN_FRAMEWORK_BUILD_TYPE`
  (<https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html>).
  Kotlin has no `debugImplementation` for native source sets: one
  dependency graph serves both framework build types. Whether a Gradle
  script can pick a dependency from that variable is not verified.

## Options

### A. Installing the plugin

1. **`install(AppMonitorKtor)`**, a `ClientPlugin<Unit>` in
   `appmonitor.sdk.ktor`, no configuration block: masking, body limit and
   the running state come from `AppMonitorOptions` through `MonitorBridge`,
   as the OkHttp interceptor does. Same gesture as `install(Logging)`; one
   public symbol to mirror in the no-op.
2. **`install(AppMonitorKtor) { maskedHeaders += … }`** with a per-client
   config — finer control, but two places to set the same option and a
   mutable config class in the public API (ADR-0017 B rejected the DSL).
3. **An extension, `HttpClientConfig<*>.appMonitor()`** — hides that it is
   a plugin; nothing gained over 1.

Recommended: 1.

### B. `AppMonitor.start` on iOS

1. **Per platform, as today.** Android keeps `start(context, options)`;
   `iosMain` adds `AppMonitor.start(options)` / `stop()`. The app calls it
   from its platform code (Android `Application.onCreate`, iOS from Kotlin
   `iosMain`, or from Swift if the app exports `sdk/core` in its framework).
   No `expect` in common: the Android signature needs a `Context`.
2. **A common `expect fun start(options)`**, Android taking its `Context`
   from an AndroidX Startup initializer — one call in `commonMain`, but the
   auto-start ADR-0017 A rejected.
3. **Common `start(platform: AppMonitorPlatform, options)`**, a holder the
   app builds per platform — a new public type for one parameter.

Recommended: 1; an app that wants one call writes its own `expect` around
it.

### C. Artifacts and the no-op

New artifact `appmonitor-ktor` (`sdk/ktor`, depends on `appmonitor-core`
and `ktor-client-core`; no engine). `appmonitor-core` gains iOS variants.

1. **One no-op per adapter, over a shared core no-op**:
   `appmonitor-okhttp-no-op` (OkHttp), `appmonitor-ktor-no-op`
   (`ktor-client-core`), both depending on `appmonitor-core-no-op`
   (`AppMonitor`, `AppMonitorOptions`). An app swaps each adapter it uses
   for its no-op; one that uses both gets `AppMonitor` once. Renames
   today's `appmonitor-no-op`, never published (ADR-0054, M10); supersedes
   ADR-0026's "one artifact".
2. **Option 1, keeping `appmonitor-no-op` as the OkHttp no-op** — no rename,
   asymmetric names.
3. **One `appmonitor-no-op` for everything** — it then depends on OkHttp
   and Ktor, so an OkHttp-only Android release pulls Ktor in, and a
   Ktor-only KMP release pulls OkHttp.

Recommended: 1.

### D. Keeping the SDK out of an iOS release

1. **The app's Gradle script picks `appmonitor-ktor` or
   `appmonitor-ktor-no-op` for `iosMain` from the build type Xcode asks for**
   (`KOTLIN_FRAMEWORK_BUILD_TYPE` or the configuration name), with a
   snippet in the docs; M4.9 proves it and M4.9's symbol scan checks it.
   Unverified (above); if it cannot work, this ADR is reopened.
2. **One dependency, `start()` returns at once when
   `Platform.isDebugBinary` is false** — no build logic, but every SDK
   class ships in the release binary, against M4.9's "release builds
   contain no SDK names".

Recommended: 1.

### E. Kotlin/Native targets and the oldest Ktor

1. **`iosArm64` and `iosSimulatorArm64`; Ktor 3.6.0 or newer.** The tier 1
   targets; an app that still declares `iosX64` cannot resolve the SDK for
   it. Compiling against the catalog's Ktor costs nothing extra: the debug
   classpath already gets it through `sdk/core`.
2. **Option 1 plus `iosX64`** — Intel simulators; tier 3, untested on CI
   here (no Intel Mac, ADR-0037).
3. **Compile against Ktor 3.0.0** to state "3.0+" — but `sdk/core`'s
   transport still raises the app to 3.6.0 in debug.

Recommended: 1.

## Decision

Empty while `Proposed`.

## Consequences

- C.1 renames the Android release dependency in `samples/android`, SDD 05
  and the dry-run (M4.8) and replaces ADR-0026's single artifact.
- B.1 adds one `iosMain` `AppMonitor` to `sdk/core`'s iOS ABI dump and its
  no-op twin.
- D.1 leaves the iOS release mechanism unproven until M4.9; a failure there
  stops M4 at a new ADR.
