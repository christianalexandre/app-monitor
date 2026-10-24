---
id: DEBT-0061
title: Non-correctness findings of the third M4 milestone review
status: Open
date: 2026-10-24
trigger: The next PR that touches `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/DeviceId.kt`, `sdk/core/src/jvmAndAndroidMain/kotlin/appmonitor/sdk/core/Platform.jvmAndAndroid.kt` or `sdk/core/src/androidMain/kotlin/appmonitor/sdk/core/Platform.android.kt`
paid_date:
---

# DEBT-0061 — Non-correctness findings of the third M4 milestone review

## What is missing

Findings of the `code-review` of `97038e3..7819fe8` that are not wrong
results, crashes, hangs, leaks, races or privacy issues against what the
SDD states today (ADR-0016):

- `/** SELinux denies `link` to apps (ADR-0080). */` in
  `Platform.android.kt` and the `FileLock` comment in
  `Platform.jvmAndAndroid.kt` restate ADR-0080's reasons instead of what
  breaks; the second cites no ADR (guidelines 02).
- `DeviceIdTest` does not say which path a writer took: on the Android
  host every writer locks, and the `.lock` file is filtered out of the
  listing, so a regression in the link path would only show on JVM or the
  iOS simulator. No test mixes a linking writer with a locking one.

## Why it was accepted

Comment style and test diagnostics: no path the SDKs take today gives a
wrong id. Linking and locking writers only mix when `link` fails with
something other than `EEXIST` beside a temp file just written in the same
directory, the case the second review already rejected.

## Trigger

The next PR that touches `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/DeviceId.kt`,
`sdk/core/src/jvmAndAndroidMain/kotlin/appmonitor/sdk/core/Platform.jvmAndAndroid.kt`
or `sdk/core/src/androidMain/kotlin/appmonitor/sdk/core/Platform.android.kt`.

## How it was paid

> Filled when closing.
