---
id: ADR-0073
title: KMP sample in three modules, the release swap per platform and compileSdk 37
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0073 — KMP sample in three modules, the release swap per platform and compileSdk 37

## Context

PLAN-0005 M4.9 builds `samples/kmp` per ADR-0065 (Compose Multiplatform, one
shared UI; Android, `iosArm64`, `iosSimulatorArm64`; the iOS debug build also
links the Swift SDK). ADR-0062 D.1 left unproven that an app's Gradle script
can keep the SDK out of an iOS release by picking `appmonitor-ktor` or
`appmonitor-ktor-no-op` from the build type Xcode asks for; if it could not,
M4 was to stop.

Evidence, 2026-10-24:

- `kotlin-gradle-plugin-2.4.21-sources.jar`, `XcodeEnvironment.buildType`:
  `CONFIGURATION` (`debug` or `release`, any case), else
  `KOTLIN_FRAMEWORK_BUILD_TYPE`; `embedAndSignAppleFrameworkForXcode` builds
  that framework.
- AGP 9.3.3 has no build types in `com.android.kotlin.multiplatform.library`
  (one Android variant), and `kotlin("multiplatform")` cannot share a module
  with `com.android.application`, so the Android app is its own module.
- `./gradlew :samples:kmp:androidApp:assembleDebug` at compileSdk 36 fails:
  `androidx.compose.*:1.12.1` (what Compose Multiplatform 1.12.2 resolves on
  Android) and `com.squareup.okhttp3:okhttp-android:5.5.0` (what
  `ktor-client-okhttp` 3.6.0 resolves) "require … compileSdk of at least 37".
  `samples/android` and the SDK stay on 36 (ADR-0025).
- Kotlin/Native keeps class and package names as UTF-16 strings in the
  binary, and its Release symbols carry no `kfun:` names: `nm` on the Release
  app finds none, the UTF-16 strings hold `appmonitor.sdk.core` and
  `AppMonitorOptions` (`scripts/kmp_sample_check.sh`).

## Options

1. **Three modules, a swap per platform.** `samples/kmp/shared` (KMP
   library: UI, calls, iOS framework `Shared`), `samples/kmp/androidApp`
   (`com.android.application`), `samples/kmp/iosApp` (XcodeGen host). iOS:
   `shared` reads the build type as the Kotlin plugin does and depends on
   `:sdk:ktor-no-op` in Release. Android: `shared` has one variant, so the
   app substitutes `:sdk:ktor` and `:sdk:core` with their no-ops in its
   `release*` configurations. Each platform keeps the swap where its build
   types live.
2. **A `debug`/`release` Gradle property passed by the Xcode script and a
   second Android library module** — more moving parts, and the property is
   what `CONFIGURATION` already says.
3. **One dependency and `Platform.isDebugBinary`** — ADR-0062 D.2, refused
   there.

## Decision

Option 1, the sample's Android modules on compileSdk 37
(`android-kmp-sample-compileSdk` in the catalog), targetSdk unchanged.
`make kmp-sample` (in `make swift`) builds both platforms in both build
types and fails when a release holds any SDK name but the no-op stubs: dex
class names on Android, `nm` (Swift) and UTF-16 strings (Kotlin) on iOS.
A Release build forced onto the real SDK fails it (shown in the PR).

## Consequences

- ADR-0062 D.1 holds: the iOS Release app holds only `AppMonitor`,
  `AppMonitorOptions` and `AppMonitorKtor` of the no-ops.
- A KMP app with `ktor-client-okhttp` 3.6.0 needs compileSdk 37 whatever the
  SDK does; the SDK adds no engine (ADR-0062 C), so this is not its floor.
- The Kotlin scan cannot tie a class name to its package, so it checks
  sentinels (`MonitorClient`, `MonitorBridge`, `MonitorSink`, any
  `appmonitor.*` package but the no-ops'); a renamed sentinel weakens it,
  and the Debug app must still show `MonitorClient` and `appmonitor.v1`.
- On a Mac, a cold `./gradlew build` links four more frameworks (2:52 for
  the two modules), and `make swift` runs `make kmp-sample` (3 min 25 s
  cold, 21 s warm).
