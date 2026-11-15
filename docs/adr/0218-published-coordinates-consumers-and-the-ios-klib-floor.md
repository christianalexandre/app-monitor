---
id: ADR-0218
title: Consumers of the published coordinates, and a Kotlin floor for iOS klibs equal to the build's
status: Accepted
date: 2026-11-14
superseded_by:
---

# ADR-0218 — Consumers of the published coordinates, and a Kotlin floor for iOS klibs equal to the build's

## Context

PLAN-0019 M10.4: before `0.1.0` is uploaded, prove that an app outside this
build installs the SDK with the README's steps, the same check to run
again against `0.1.0` once it is on Central. DEBT-0117: the Kotlin 2.2
floor of ADR-0169 was measured by a JVM consumer only, never by an iOS
target reading the klibs.

Evidence, measured on 2026-11-14 with `scripts/consumers_check.sh --local`
(SDK built by Kotlin 2.4.21, Gradle 9.7.1, AGP 9.3.3):

- A KMP module on `appmonitor-ktor` with `iosSimulatorArm64` on Kotlin
  2.2.0: `incompatible ABI version '2.4.0'. The library was produced by
  '2.4.21' compiler. The current Kotlin compiler can consume libraries
  having ABI version <= '2.2.0'`. On 2.3.20 the same with `<= '2.3.0'`.
  On 2.4.0 it compiles and links debug and release frameworks.
- `kotlinc-native -X` (2.4.21) on `-Xklib-abi-version`: "This option does
  not affect KLIB ABI. Neither allows it making a KLIB backward-compatible
  with older ABI versions." ADR-0169's `languageVersion` 2.3 does not lower
  the klib ABI either (the 2.2.0 run above had it).
- The JVM and Android artifacts keep ADR-0169's floor: `make
  publish-dry-run` compiles a Kotlin 2.2.0 JVM consumer against them.
- An Android app applying only the plugin builds debug and release; the
  debug runtime classpath resolves `appmonitor-core` and
  `appmonitor-okhttp`, the release one only their `-no-op`s.
- A SwiftPM package resolves the repository at the tag with *Up to Next
  Minor* and builds in debug and release.

## Options

The iOS floor:

1. **State it as the build's Kotlin minor** (chosen) — the README says a
   KMP app that reads `appmonitor-ktor` on iOS (an engine other than
   Darwin, ADR-0158) needs Kotlin 2.4 or newer; the consumer measures that
   floor on every run. Darwin users read no klib and keep any Kotlin.
2. **Build the SDK's iOS klibs with Kotlin 2.2** — a second build with
   its own Kotlin Gradle plugin for the SDK modules only, while the
   desktop, the protocol's JVM code and AGP 9.3 stay on 2.4; every Kotlin
   bump twice.
3. **Stop publishing `appmonitor-ktor`'s iOS klibs** — drops a
   documented feature (ADR-0062), a product scope change.

The check itself:

4. **A script of throwaway consumers** (chosen) — Android with the plugin
   only, KMP on `appmonitor-ktor` (JVM and iOS), SwiftPM at the tag.
   `--local` publishes to `~/.m2` and tags a scratch bare clone named
   `app-monitor.git`, so nothing is pushed and the repository has no tag
   before the owner's.
5. **More TestKit tests** — they stand in `:sdk:*` projects for the
   artifacts (ADR-0126), which is what this check must not do.

## Decision

Options 1 and 4. `scripts/consumers_check.sh [--local] [VERSION]` builds
the three consumers; the KMP one runs on `CONSUMER_KOTLIN`, by default the
`major.minor.0` of the build's own Kotlin. The README states the iOS klib
floor beside ADR-0169's. Not in `make check` (≈1 min warm, Kotlin/Native
and Android SDK needed); M10.6 runs it against the published `0.1.0`.

## Consequences

- DEBT-0117 is paid: the floor it feared is measured and documented.
- Each Kotlin minor bump of this build raises the iOS floor for KMP apps
  on a non-Darwin engine; the release notes say so.
- The consumers need `ANDROID_HOME`, and macOS with Xcode for the iOS and
  SwiftPM parts (skipped elsewhere).
