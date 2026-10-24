---
id: ADR-0026
title: One no-op artifact, checked against committed ABI dumps
status: Superseded
date: 2026-10-22
superseded_by: ADR-0072
---

# ADR-0026 — One no-op artifact, checked against committed ABI dumps

## Context

Release builds link a no-op artifact with the same public API as the SDK
(guidelines 01, ADR-0017). The Android SDK's public API spans two modules:
`sdk/core` (`AppMonitor`, `AppMonitorOptions`) and `sdk/android-okhttp`
(`AppMonitorInterceptor`, `AppMonitorWebSocketFactory`). `MonitorBridge` and
`InternalAppMonitorApi` are public only for capture modules (ADR-0025).
PLAN-0002 M1.8 asks for a check that fails when either side gains a public
member.

Evidence, checked on 2026-10-22 against the Kotlin Gradle plugin 2.4.21 in
use:

- The plugin ships ABI validation (`kotlin { abiValidation { } }`,
  `org.jetbrains.kotlin.gradle.dsl.abi` in `kotlin-gradle-plugin-api`). It
  writes one dump per JVM-like target (`api/android/<module>.api`,
  `api/jvm/<module>.api`) with `updateKotlinAbi`, and `checkKotlinAbi`, a
  dependency of `check`, fails when the code no longer matches the committed
  dump. Filters exclude declarations by name or annotation. `enabled` and
  `excluded` are deprecated in this version (compiler warnings).
- Dumps hold signatures, not default values: the options' defaults (port,
  body limit) are invisible to them.
- The dumper omitted `AppMonitorInterceptor`'s public no-arg constructor while
  the class had an `internal` primary constructor and a public secondary
  `constructor() : this(MonitorSink)` — `javap` showed the constructor
  public. With two secondary constructors and no primary, it is listed.

## Options

1. **ABI dumps from the Kotlin Gradle plugin, committed for every SDK module;
   a JVM test in `sdk/no-op` compares its dumps with `sdk/core` +
   `sdk/android-okhttp`, per target.** No new dependency; public API changes
   show up as a diff in the PR — useful for a "stop and ask" area. Inherits
   the dumper's blind spots, on both sides.
2. **Reflection test loading both artifacts in separate class loaders.** Exact
   to the bytecode, but needs both Android classpaths outside Gradle's test
   setup, and the same FQ names on two class paths.
3. **One no-op artifact per real module** (`sdk/core-no-op`,
   `sdk/android-okhttp-no-op`). Mirrors the module split, but an app swaps
   two dependencies for one SDK, and `core` alone is useless in an app.

## Decision

Option 1, with one artifact, `sdk/no-op`, mirroring everything an app touches
in `sdk/core` and `sdk/android-okhttp` (Android and JVM targets, depends on
OkHttp only). `appmonitor.kmp-sdk` turns ABI validation on for every SDK
module and excludes `InternalAppMonitorApi`. `OptionsDefaultsTest` checks the
defaults the dumps cannot see. `AppMonitorInterceptor` declares both
constructors as secondary so its public one is in the dump (DEBT-0026).

## Consequences

- A public API change runs `./gradlew updateKotlinAbi` and updates
  `sdk/no-op` in the same PR, or `check` fails.
- An app depends on `sdk/android-okhttp` in debug and `sdk/no-op` in release.
- A future SDK adapter (Ktor, M3) joins the comparison list in
  `ApiMatchTest`.
- The dumper's omission of a public no-arg secondary constructor beside a
  non-public primary is a blind spot for both sides (DEBT-0026).
