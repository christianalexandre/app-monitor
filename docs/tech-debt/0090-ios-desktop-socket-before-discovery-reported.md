---
id: DEBT-0090
title: A desktop socket opened before the iOS SDK finds that desktop is reported
status: Dropped # Open | Paid | Dropped
date: 2026-10-25
trigger: The first field-test session that shows a `CONNECTION` row to the desktop's own address and port, or the next PR that changes `ConnectionCapture` in `sdk/ios/Sources/AppMonitor/ConnectionCapture.swift`
paid_date: 2026-11-03
---

# DEBT-0090 — A desktop socket opened before the iOS SDK finds that desktop is reported

## What is missing

`ConnectionCapture` skips a socket to a desktop the Swift SDK found by
mDNS or was given as its host (ADR-0100 C.1, SDD 07). A socket another
SDK of the app, `sdk/core` in a KMP app, connects to a desktop **before**
the Swift SDK's first answer for it is reported once: a `CONNECTION` row
to the desktop's address and port 47474.

## Why it was accepted

Both SDKs browse the same service when the app starts them, and
`sdk/core` connects only after its own answer, TXT wait and probe, so the
Swift SDK's answer comes first unless the app starts it much later. Telling
the socket apart without the answer means reading what it writes (the
WebSocket upgrade to the desktop's path), which the C wrappers do not do.

## Trigger

The first field-test session that shows a `CONNECTION` row to the
desktop's own address and port, or the next PR that changes
`ConnectionCapture` in `sdk/ios/Sources/AppMonitor/ConnectionCapture.swift`.

## How it was paid

Dropped by PLAN-0016 H6.4a, without a change. The trigger's row has not shown in any session, and
it needs an app starting the Swift SDK well after `sdk/core`. Its cost is one extra `CONNECTION`
row to a desktop. Telling that socket apart needs reading what it writes, which the C wrappers
do not do, or skipping port 47474 on every address, which would hide an app's own socket there:
a rule about what is captured, for a row nobody has seen.
