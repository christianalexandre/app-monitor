---
id: DEBT-0030
title: The SDK trusts the implicit local network grant and fails silently without it
status: Open
date: 2026-10-22
trigger: PLAN-0002 M1.12 starts (end to end on emulator and device), or a developer reports an app that never reaches the desktop
paid_date:
---

# DEBT-0030 — The SDK trusts the implicit local network grant and fails silently without it

## What is missing

`localNetworkGranted` (`sdk/core`, `AndroidDevice.kt`) treats any app
targeting below 37 as granted. On the Android 17 emulator the grant was
missing for a target-36 app: mDNS found nothing and the `10.0.2.2` connect
timed out (ADR-0030). No log line says so, because `MonitorClient.connect`
swallows connect exceptions and the "mDNS skipped" line is never reached.
Symptom: the app runs, the desktop never sees it, logcat shows no
`AppMonitor` line.

## Why it was accepted

M1.11 is the sample; the sample asks for the permission itself, which ADR-0017
E already makes the app's job. Fixing the check and logging connect failures
changes `sdk/core` behaviour, and belongs with the end-to-end task.

## Trigger

PLAN-0002 M1.12 starts, or a developer reports an app that never reaches the
desktop. Then check the permission whenever the device runs Android 17+, and
log the first connect failure per client.

## How it was paid

> Filled when closing.
