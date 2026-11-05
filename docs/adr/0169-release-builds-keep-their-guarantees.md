---
id: ADR-0169
title: The no-op check reads constructors from the classes, the plugin rewrites OkHttp for its own SDK version, and the SDK compiles to Kotlin 2.3
status: Accepted
date: 2026-10-31
superseded_by:
---

# ADR-0169 — The no-op check reads constructors from the classes, the plugin rewrites OkHttp for its own SDK version, and the SDK compiles to Kotlin 2.3

## Context

Three guarantees cost more once an artifact is published (PLAN-0015 H5.5).

**A. A constructor missing from the dumps.** `ApiMatchTest` compares the
Kotlin plugin's ABI dumps (ADR-0026, ADR-0072). Measured on 2026-10-31 with
Kotlin 2.4.21: a class `Probe internal constructor(x: Int)` with a public
`constructor() : this(0)` is dumped as `public final class Probe {}`, while
`javap` shows `public Probe()`. With the real module and the no-op both
dumped that way, the no-op could drop the constructor and the check stayed
green. An app would then compile in debug and fail in release (DEBT-0026).

**B. The rewrite's entry points.** The plugin rewrites `OkHttpClient` to call
`AppMonitorOkHttp.install` and `.newWebSocket` whenever `appmonitor-okhttp`
is in the graph, whatever its version (ADR-0112, ADR-0159). An app that
forces an older SDK, through a constraint or a BOM, gets a
`NoSuchMethodError` at runtime for any method the plugin calls but that
SDK lacks (DEBT-0099).

**C. The Kotlin an app needs.** The SDK was compiled with Kotlin 2.4.21, so its
classes carried metadata 2.4.0, and its POMs asked for `kotlin-stdlib` 2.4.21.
A compiler reads metadata at most one version ahead, so AGP 9.3.3's built-in
Kotlin 2.2 failed, and the README asked for an extra root plugin line
(ADR-0162, DEBT-0108). Measured on 2026-10-31, the metadata version of the
SDK's dependencies (`javap -v`, `mv=`): Ktor 3.6.0 and kotlinx-io 0.9.1 2.3.0,
kotlinx-coroutines 1.11.0 and OkHttp 5.3.2 2.2.0, Wire runtime 7.1.0 2.0.0.
Ktor 3.6.0's POM asks for `kotlin-stdlib` 2.3.21.

## Options

A.1 **Report the dumper's gap and wait.** Leaves the gap until an upstream fix.
A.2 **Compare constructors read from the compiled JVM classes.** ASM reads
    each class's `@kotlin.Metadata`, then `kotlin-metadata-jvm` gives each
    constructor's Kotlin visibility and JVM signature. That excludes
    `internal` constructors, which are public in bytecode. Two test-only
    dependencies: ASM (BSD-3-Clause) and `kotlin-metadata-jvm` (Apache-2.0,
    released with Kotlin).
A.3 **Reflection over two class loaders.** Needs each module's full runtime
    classpath to load a class whose supertype is OkHttp's or Ktor's.

B.1 **Rewrite only when the resolved `appmonitor-okhttp` is the plugin's
    version, and fail the debug build otherwise**, with one line naming
    both versions and the opt-out. This is DEBT-0099's own fix.
B.2 **Rewrite when the resolved version is the plugin's or newer.** Needs a
    version comparator, and keeps a newer SDK with an older plugin
    possible. Nothing requires that today.

C.1 **State Kotlin 2.3 as the floor.** Keeps the extra line for AGP 9's
    built-in Kotlin.
C.2 **Compile the published modules (`appmonitor.kmp-sdk` and
    `appmonitor.kmp-library`) with `languageVersion` and `apiVersion` 2.3, and
    `coreLibrariesVersion` 2.3.21.** `kotlin.uuid` is opted in, since it is
    experimental in API 2.3 and used only internally. Measured: the SDK
    builds, its classes carry metadata 2.3.0, its POMs ask for stdlib
    2.3.21. A JVM consumer on Kotlin 2.2.0 compiles against
    `appmonitor-core`, `-okhttp` and `-ktor`. On 2.1.21 it fails on both the
    SDK's metadata and Ktor 3.6.0's, so with these dependencies 2.2 is the
    floor.

## Decision

A.2, B.1 and C.2. C.2 removes a setup line (the root Kotlin plugin for AGP
9's built-in Kotlin), under the standing owner decision "Short setup". No
SDK's public API changes: the dumps are unchanged, and the plugin's new task
is `internal`.

## Consequences

- `ConstructorMatchTest` in `sdk/no-op` gets each module's JVM jar through
  one configuration per module. It compares the public and protected
  constructors of each class the real module's JVM dump lists. A crafted
  `Probe` failed it while `ApiMatchTest` passed. Pays DEBT-0026.
- In a debug variant that rewrites OkHttp, `appMonitorOkHttpCheck<Variant>`
  runs before `pre<Variant>Build` and fails when `appmonitor-okhttp`
  resolves to another version. Opting out (`injectOkHttp = false`) skips
  both the rewrite and the check. Pays DEBT-0099.
- `sdk-kotlin-api` and `sdk-kotlin-stdlib` in `gradle/libs.versions.toml`
  hold the floor. `make publish-dry-run` compiles a consumer on Kotlin 2.2.0
  against the published files, so a dependency that raises the floor fails
  there. The README states Kotlin 2.2, and the extra line is gone. Pays
  DEBT-0108.
- Kotlin/Native klibs follow the same language version. No iOS consumer on
  Kotlin 2.2 was compiled: the floor is measured on the JVM, which covers
  Android.
