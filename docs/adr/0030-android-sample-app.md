---
id: ADR-0030
title: Android sample app, its variants and the release classpath check
status: Accepted
date: 2026-10-22
superseded_by:
---

# ADR-0030 — Android sample app, its variants and the release classpath check

## Context

PLAN-0002 M1.11 asks for `samples/android`: a Compose app whose buttons
make OkHttp calls captured by `sdk/android-okhttp` in debug, with `sdk/no-op`
in release (ADR-0026) and no `sdk/core` classes in the release APK. Already
decided: the public API (ADR-0017), the no-op artifact (ADR-0026),
`compileSdk` 36 and AGP 9.3 (ADR-0007).

Evidence, checked on 2026-10-22:

- Google Maven: Compose BOM `2026.08.00` and later map to Compose 1.12, whose
  `runtime-saveable-android` AAR needs `compileSdk` 37 (AGP's
  `checkDebugAarMetadata` failed with it); BOM `2026.06.01` maps to Compose
  1.11.4, `minCompileSdk=35`. `activity-compose` 1.13.0: `minCompileSdk=36`.
- Android 17 emulator (`Medium_Phone`, API 37, `sdk_gphone16k_arm64`): the
  sample, targeting 36, could not open a socket to `10.0.2.2:47474`
  (`ConnectTimeoutException`) and mDNS found nothing, while
  `adb shell nc 10.0.2.2 47474` connected. `dumpsys package` showed
  `ACCESS_LOCAL_NETWORK: granted=false`. After `pm grant` the SDK found the
  desktop through mDNS and connected; after `pm revoke` it failed again. The
  Android docs (developer.android.com/privacy-and-security/local-network-permission)
  say apps targeting 36 or lower get an implicit grant; this image did not.
- `dexdump` of the release APK built with `sdk/no-op` lists only
  `AppMonitor`, `AppMonitorOptions` (+ `Companion`), `AppMonitorInterceptor`,
  `AppMonitorWebSocketFactory` under `appmonitor/`, and no `io/ktor` class.

## Options

1. **Gate the release classpath in Gradle**: a `checkReleaseHasNoSdk` task,
   in `check`, fails when `releaseRuntimeClasspath` resolves a `:sdk:*`
   project other than `:sdk:no-op`. Cheap, no APK build needed to fail.
2. **Inspect the release APK's dex** in a test. Exact, but needs build tools
   on the path of a Gradle task and an assembled APK in every `check`.
3. **Check by hand** in the PR only. Nothing stops a later regression.

## Decision

`samples/android` applies a new convention plugin `appmonitor.android-app`
(AGP application, Compose compiler, lint; `minSdk` 24, `compileSdk` and
`targetSdk` 36). Compose BOM pinned to `2026.06.01` until the project moves to
`compileSdk` 37. Debug depends on `sdk/android-okhttp`, release on `sdk/no-op`;
option 1 guards it, and the APK's dex was checked by hand once (above). The
debug variant asks for `ACCESS_LOCAL_NETWORK` at launch on Android 17+ and
allows cleartext in a debug-only network security config (ADR-0017 E, F);
the release variant asks for nothing. Buttons call `httpbin.org` and
`echo.websocket.org`, public services the sample does not control.

## Consequences

- `make check` builds both sample APKs and runs Android lint on the sample.
- The sample's buttons fail without internet; each failure is still a
  captured `RequestFailed`.
- The SDK assumes the implicit grant below target 37 and logs nothing when it
  is missing; connect failures are silent too (DEBT-0030).
