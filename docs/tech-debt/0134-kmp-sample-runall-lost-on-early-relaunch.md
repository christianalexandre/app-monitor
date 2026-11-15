---
id: DEBT-0134
title: The KMP sample's runAll is lost when Android relaunches its activity at start
status: Open
date: 2026-11-15
trigger: A second `e2e-kmp-android` failure in `release-gate.yml` where every call is "no RequestStarted", or the next PR that changes `samples/kmp/androidApp/src/main/kotlin/appmonitor/sample/kmp/MainActivity.kt`
paid_date:
---

# DEBT-0134 — The KMP sample's runAll is lost when Android relaunches its activity at start

## What is missing

`MainActivity` reads `runAll` only when `savedInstanceState == null`, so that
a rotation does not run every call again. When the system relaunches the
activity within the first second after `am start`, the first instance's
`LaunchedEffect` is cancelled before its first call. The relaunched instance
has a saved state, so it does not run them.

Symptom: `make e2e-kmp-android` fails after `E2E_TIMEOUT` with
`missing: … no RequestStarted` for every call. In the server log the device
connects with an empty `DeliveryReport`, and logcat has `finishDrawing of
relaunch` for `MainActivity`. This happened once, on the `release/0.1.0` PR's
release gate (2026-11-15). Rerunning only that job passed.

## Why it was accepted

It has happened in one run, and the gate runs it twice a week. A fix has to
keep `runAll` across the relaunch without running a call twice: the end to
end asserts each envelope once. That fix is not a one-line change, and it
does not belong in a release PR.

## Trigger

A second `e2e-kmp-android` failure in `release-gate.yml` with every call
"no RequestStarted", or the next PR that changes
`samples/kmp/androidApp/src/main/kotlin/appmonitor/sample/kmp/MainActivity.kt`.

## How it was paid

> Filled when closing.
