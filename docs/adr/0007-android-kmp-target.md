---
id: ADR-0007
title: Android target through the Android KMP library plugin
status: Accepted
date: 2026-10-21
superseded_by:
---

# ADR-0007 — Android target through the Android KMP library plugin

## Context

Shared KMP modules (protocol, SDK core) must compile for Android. ADR-0006
left the Android target out of `appmonitor.kmp-library` until the first module
needed it; the protocol module (PLAN-0001 M0.2) is that module.

Evidence, checked on 2026-10-21:

- Latest AGP on Google Maven is 9.4.1 (`maven-metadata.xml` of
  `com.android.tools.build:gradle`), but Kotlin 2.4.21 documents AGP up to
  **9.3.1** (`kotlin-web-site` `docs/v.list`, `maxAndroidGradleVersion`).
  The newest 9.3 patch is 9.3.3.
- AGP 9.3 needs Gradle ≥ 9.5.0, JDK 17 and build tools 36.0.0; it supports
  API levels up to 37 (AGP 9.3.0 release notes). The wrapper is 9.7.1.
- For KMP, Android documents `com.android.kotlin.multiplatform.library`
  with a `kotlin { android { } }` block; `androidLibrary { }` is deprecated
  since AGP 9.1 (developer.android.com/kotlin/multiplatform/plugin).

## Options

1. **`com.android.library` + `androidTarget()`** — the legacy pairing;
   Android steers KMP libraries away from it.
2. **`com.android.kotlin.multiplatform.library`** — the documented plugin
   for KMP libraries; one Android variant, host tests opt-in.

## Decision

Option 2 with AGP **9.3.3** (newest patch of the newest minor that Kotlin
2.4.21 documents). `appmonitor.kmp-library` applies it with `compileSdk` 36
and `minSdk` 21 from the version catalog, and enables host tests so
`commonTest` also runs on the Android JVM. Each module sets its `namespace`.

## Consequences

- `allTests` runs common tests on JVM, Android host and iOS simulator.
- Building any KMP module needs an Android SDK (`ANDROID_HOME`).
- `minSdk` 21 is a floor for shared code only; the SDK's own `minSdk` is
  decided with the Android SDK (roadmap M1). AGP upgrades wait for the
  Kotlin compatibility table, like Gradle (ADR-0006).
