---
id: DEBT-0060
title: Non-correctness findings of the second M4 milestone review
status: Open
date: 2026-10-24
trigger: The next PR that touches `sdk/core/src/commonTest/kotlin/appmonitor/sdk/core/MonitorClientTest.kt` or `scripts/e2e_lib.sh`
paid_date:
---

# DEBT-0060 — Non-correctness findings of the second M4 milestone review

## What is missing

Findings of the `code-review` of `ad3e5a7..97038e3` that are not wrong
results, crashes, hangs, leaks, races or privacy issues against what the
SDD states today (ADR-0016):

- `MonitorClientTest`'s `client()` helper builds `MonitorClient` in two
  branches on `openSession == null`; a default lambda would keep one call.
- `scripts/e2e_lib.sh`'s `sessions` matches the app id in any field of a
  `connected:` line rather than its own field. Both SDKs send `sdk_name`
  (ADR-0077) and no device name token equals an app id, so no run
  miscounts today.

## Why it was accepted

Documentation precision, duplication and test-harness robustness: none
gives a wrong result on any path the SDKs or the end to ends take now.

The first two findings (the `createParentDirectories` KDoc, and the JVM
and Android copies of `Platform`) were paid in PLAN-0005 M4.R7: the KDoc
says the call may throw, and the shared code is in
`sdk/core/src/jvmAndAndroidMain` (ADR-0080).

## Trigger

The next PR that touches `sdk/core/src/commonTest/kotlin/appmonitor/sdk/core/MonitorClientTest.kt` or `scripts/e2e_lib.sh`.

## How it was paid

> Filled when closing.
