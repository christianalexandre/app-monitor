---
id: DEBT-0047
title: The Android and iOS end to end depend on public services
status: Open
date: 2026-10-23
trigger: The first `make e2e-android` or `make e2e-ios` failure caused by `httpbin.org` or `echo.websocket.org` (a gate run or a local one)
paid_date:
---

# DEBT-0047 — The Android and iOS end to end depend on public services

## What is missing

`make e2e-android` and `make e2e-ios` (ADR-0057, since M3.7) run the
samples' real buttons, which call `httpbin.org` and `echo.websocket.org`
(ADR-0030, ADR-0050). When either is down or slow, the run
fails with `no ResponseReceived` or `no RequestStarted` lines although the
SDK and the desktop are fine.

## Why it was accepted

The test exercises what an app developer sees, real TLS calls included, and
every run on 2026-10-23 passed. A local HTTP and WebSocket target on the
host would need a second server and sample URLs switched by an extra.

## Trigger

The first `make e2e-android` or `make e2e-ios` failure caused by
`httpbin.org` or `echo.websocket.org`, in the gate (M3.6, M3.8) or locally.
Then point the samples at a server the scripts start on the host (reached at
`10.0.2.2` from the emulator, `127.0.0.1` from the simulator), with the
public URLs left as the default.

## How it was paid

> Filled when closing.
