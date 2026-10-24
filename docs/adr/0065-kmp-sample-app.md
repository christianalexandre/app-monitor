---
id: ADR-0065
title: KMP sample app, its UI toolkit, targets and place in the release gate
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0065 — KMP sample app, its UI toolkit, targets and place in the release gate

## Context

PLAN-0005 M4.1 (d). `samples/kmp` (M4.9) runs every call of the other
samples through Ktor on Android and iOS, and `make e2e-kmp` (M4.10) drives
it. The sample is product surface (what the repository demonstrates) and the
gate job is CI spend, so the owner decides (`AGENTS.md`).

Already decided, not reopened here:

- The Android and iOS samples: a `runAll` launch path, release builds
  without SDK names, end to ends asserted on the headless server log
  (ADR-0030, ADR-0050, ADR-0056, ADR-0057).
- The iOS sample is an XcodeGen project (ADR-0050).
- The repository is public; standard GitHub runners cost nothing, and "no
  CI spend, ever" (ADR-0052; `gh api repos/christianalexandre/app-monitor`
  → `"private": false`).
- Compose Multiplatform 1.12.2 is already in the catalog for the desktop
  (`gradle/libs.versions.toml`).

Evidence, release gate run of 2026-10-24 (`gh run view`, last green run):
`e2e-android` (ubuntu) 2.8 min, `e2e-ios` (macOS) 9.7 min, `contract`
(macOS) 11.1 min; the gate's wall time is its slowest job, ≈11 min.

## Options

### A. UI toolkit

1. **Compose Multiplatform, one shared UI** in `commonMain`, a thin Android
   activity and an iOS XcodeGen host that embeds the framework
   (`embedAndSignAppleFrameworkForXcode`). One screen, one `runAll`, the way
   a KMP app is usually written today.
2. **Shared Ktor module, native UIs** (Jetpack Compose and SwiftUI) — two
   UIs and two `runAll` paths for a sample whose point is the network
   layer.

Recommended: 1.

### B. Targets

1. **Android, `iosArm64`, `iosSimulatorArm64`** (ADR-0062 E); the iOS debug
   build also links the Swift SDK, so the dedupe of ADR-0064 is exercised
   by the end to end.
2. **Option 1 plus JVM desktop** — another target nobody asked for.

Recommended: 1.

### C. `make e2e-kmp` in the release gate

1. **Two jobs**: `e2e-kmp-android` on ubuntu, `e2e-kmp-ios` on macOS,
   parallel. Free on a public repository; estimated from the existing jobs
   ≈3–4 and ≈10–12 min, so the gate's wall time stays ≈12 min.
2. **Local only** (`make e2e-kmp` on the Mac before a release) — no gate
   job; a KMP regression can reach `main`.

Recommended: 1.

## Decision

The owner accepted every recommendation: A.1 (Compose Multiplatform, one
shared UI), B.1 (Android, `iosArm64`, `iosSimulatorArm64`) and C.1
(`e2e-kmp-android` and `e2e-kmp-ios` jobs in the release gate).

## Consequences

- A.1 adds Compose Multiplatform for iOS to the sample only; the SDK
  modules stay UI-free.
- C.1: M4.10 reports the measured minutes of both jobs, replacing the
  estimate.
