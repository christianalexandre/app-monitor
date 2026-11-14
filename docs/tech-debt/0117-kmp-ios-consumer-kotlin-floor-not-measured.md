---
id: DEBT-0117
title: No KMP app's iOS target on Kotlin 2.2 was compiled against the SDK's klibs
status: Open
date: 2026-10-31
trigger: "PLAN-0019 M10.4's PR, or a KMP app on Kotlin 2.2 reports a klib error from `appmonitor-ktor` or `appmonitor-core`"
paid_date:
---

# DEBT-0117 — No KMP app's iOS target on Kotlin 2.2 was compiled against the SDK's klibs

## What is missing

The README states Kotlin 2.2 as the floor (ADR-0169). That was measured
by a JVM consumer, in `make publish-dry-run`. The SDK's iOS klibs are built
with the same language version, but no iOS target on Kotlin 2.2 has
compiled against them. Symptom: a KMP app on Kotlin 2.2 that applies the
plugin in a module with iOS targets fails to read `appmonitor-ktor` or
`appmonitor-core`.

## Why it was accepted

A KMP app on Darwin and OkHttp needs no SDK in `commonMain` (ADR-0158), so
only an app on another Ktor engine reads the klibs. Measuring needs the
Kotlin/Native 2.2 toolchain downloaded on every dry-run machine.

## Trigger

PLAN-0019 M10.4's PR, or a KMP app on Kotlin 2.2 reports a klib error
from `appmonitor-ktor` or `appmonitor-core`. Then give M10.4's KMP
consumer an `iosSimulatorArm64` target on Kotlin 2.2.

Re-triggered on 2026-11-14 (M10.1): PLAN-0019 was written, and M10.4
already builds a KMP consumer on `appmonitor-ktor`, so the iOS target on
Kotlin 2.2 goes there instead of in a second consumer.

## How it was paid

> Filled when closing.
