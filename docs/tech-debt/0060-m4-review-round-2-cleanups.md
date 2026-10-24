---
id: DEBT-0060
title: Non-correctness findings of the second M4 milestone review
status: Open
date: 2026-10-24
trigger: The next PR that touches `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/DeviceId.kt`, `sdk/core/src/jvmMain/kotlin/appmonitor/sdk/core/Platform.jvm.kt`, `sdk/core/src/commonTest/kotlin/appmonitor/sdk/core/MonitorClientTest.kt` or `scripts/e2e_lib.sh`
paid_date:
---

# DEBT-0060 — Non-correctness findings of the second M4 milestone review

## What is missing

Findings of the `code-review` of `ad3e5a7..97038e3` that are not wrong
results, crashes, hangs, leaks, races or privacy issues against what the
SDD states today (ADR-0016):

- `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/DeviceId.kt`: the
  KDoc of `createParentDirectories` says a racing writer creating the
  directories is not an error, but the JVM and Android actuals call
  kotlinx-io's `createDirectories`, which can throw when a racing `mkdir`
  wins. `publish` wraps the call in `runCatching`, so no caller sees it
  today; the KDoc overstates the contract.
- `linkExclusive` and `createParentDirectories` are the same code in
  `Platform.android.kt` and `Platform.jvm.kt` (the Android one adds the
  `Os.link` fallback); a fix to one can miss the other.
- `MonitorClientTest`'s `client()` helper builds `MonitorClient` in two
  branches on `openSession == null`; a default lambda would keep one call.
- `scripts/e2e_lib.sh`'s `sessions` matches the app id in any field of a
  `connected:` line rather than its own field. Both SDKs send `sdk_name`
  (ADR-0077) and no device name token equals an app id, so no run
  miscounts today.

## Why it was accepted

Documentation precision, duplication and test-harness robustness: none
gives a wrong result on any path the SDKs or the end to ends take now.

## Trigger

The next PR that touches `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/DeviceId.kt`, `sdk/core/src/jvmMain/kotlin/appmonitor/sdk/core/Platform.jvm.kt`, `sdk/core/src/commonTest/kotlin/appmonitor/sdk/core/MonitorClientTest.kt` or `scripts/e2e_lib.sh`.

## How it was paid

> Filled when closing.
