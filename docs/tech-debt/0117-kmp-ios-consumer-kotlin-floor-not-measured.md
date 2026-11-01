---
id: DEBT-0117
title: No KMP app's iOS target on Kotlin 2.2 was compiled against the SDK's klibs
status: Open
date: 2026-10-31
trigger: "M10's plan is written, or a KMP app on Kotlin 2.2 reports a klib error from `appmonitor-ktor` or `appmonitor-core`"
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

M10's plan is written, or a KMP app on Kotlin 2.2 reports a klib error
from `appmonitor-ktor` or `appmonitor-core`. Then add an
`iosSimulatorArm64` consumer on Kotlin 2.2 to `scripts/publish_dry_run.sh`.

## How it was paid

> Filled when closing.
