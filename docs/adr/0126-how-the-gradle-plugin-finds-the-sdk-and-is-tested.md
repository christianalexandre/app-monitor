---
id: ADR-0126
title: How the Gradle plugin finds the SDK and is tested
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0126 — How the Gradle plugin finds the SDK and is tested

## Context

ADR-0112 decided what the plugin does: rewrite OkHttp's
`OkHttpClient.Builder.build()` with scope `ALL` in debuggable variants
whose runtime classpath has `appmonitor-okhttp` (B.1), in an included
build `gradle-plugin/` with TestKit tests (E.1). Left open: how the plugin
knows the SDK is there, and how the tests show the bytes changed.

Evidence, 2026-10-25, AGP 9.3.3 (`javap` on `gradle-api-9.3.3.jar`, and
the functional test of this PR):

- `Component.debuggable` and `Component.instrumentation.transformClassesWith`
  exist on every variant; `ClassContext.loadClassData(name)` looks a class
  up on the variant's classpath.
- A first version asked `loadClassData` for `AppMonitorOkHttp` inside the
  transform. In a consumer with two apps, one with the SDK and one
  without, neither APK had the call: AGP transformed OkHttp's jar once for
  both, because the classpath is not an input of the dependency
  transform. Only the factory's parameters are.
- A parameter computed from the variant's resolved files failed the
  build: "Could not isolate parameters … AsmClassesTransform", the SDK's
  jar not yet built. A transform's parameters are read before tasks run.

## Options

### A. How the plugin knows the SDK is there

1. **A parameter from the variant's dependency graph.**
   `runtimeConfiguration.incoming.resolutionResult` is metadata only. The
   SDK is a component of group `io.github.christianalexandre` named
   `appmonitor-okhttp`, `appmonitor-okhttp-android` (the published module
   and its Android variant) or `android-okhttp` (this repository's
   project). It is an input, so AGP keeps one transformed OkHttp per
   answer. Cost: a renamed or relocated SDK is not seen.
2. **`ClassContext.loadClassData`.** Wrong as soon as two modules share
   OkHttp's transform (evidence above).
3. **Scan the resolved jars for the class.** Its parameter needs the
   SDK's jar built before the transform is set up (evidence above).

Recommended: 1.

### B. How the tests show the rewrite

1. **A unit test and one TestKit build, read with `dexdump`.** The unit
   test rewrites the real `OkHttpClient$Builder.class`, loads it, and
   counts the calls to a stand-in entry point. The TestKit build makes a
   consumer with three apps: one with a stand-in SDK, one with
   `injectOkHttp = false`, one without the SDK. It runs `assembleDebug`
   and `assembleRelease`, then counts `invoke-static` calls to
   `AppMonitorOkHttp.install` in each APK with the SDK's `dexdump`. AGP
   comes from the plugin's classpath, so the TestKit home downloads only
   what the consumer resolves.
2. **Read the transformed classes from AGP's caches.** Their paths are
   internal to AGP.

Recommended: 1.

### C. Where the slow test runs

1. **`functionalTest` in `make android-samples`** (release gate `samples`
   job). The unit test and the plugin's ABI check run in `make check`
   through the root `check`.
2. **All in `make check`.** It adds four Android builds to every PR.

Recommended: 1.

### D. The plugin's bytecode

1. **JVM 17**, the oldest JDK AGP 9 runs on, compiled with the repository's
   toolchain; AGP's `gradle-api` `compileOnly`, so the app's AGP is used.
2. **JVM 21**, as the rest of the build.

Recommended: 1.

## Decision

A.1, B.1, C.1, D.1. Technical, inside ADR-0112; accepted by the agent.

## Consequences

- The line "OkHttp is not rewritten" is printed when AGP runs the
  transform for a variant without the SDK, not on a build that reuses
  its cached output.
- An Android app with both `appmonitor-ktor` and `appmonitor-okhttp`
  gets the interceptor in Ktor's OkHttp engine too, and each call twice
  (DEBT-0087).
- The published names are matched but not built against until M10
  publishes them; the roadmap's M10 has the test.
- `make android-samples` takes about 15 s more with a warm TestKit home;
  a cold one downloads the consumer's OkHttp and AAPT2 once.
