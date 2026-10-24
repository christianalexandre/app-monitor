---
id: DEBT-0047
title: The Android end to end depends on public services
status: Open
date: 2026-10-23
trigger: The first `make e2e-android` failure caused by `httpbin.org` or `echo.websocket.org` (a gate run or a local one)
paid_date:
---

# DEBT-0047 — The Android end to end depends on public services

## What is missing

`make e2e-android` runs the sample's real buttons, which call `httpbin.org`
and `echo.websocket.org` (ADR-0030). When either is down or slow, the run
fails with `no ResponseReceived` or `no RequestStarted` lines although the
SDK and the desktop are fine.

## Why it was accepted

The test exercises what an app developer sees, real TLS calls included, and
every run on 2026-10-23 passed. A local HTTP and WebSocket target on the
host would need a second server and sample URLs switched by an extra.

## Trigger

The first `make e2e-android` failure caused by `httpbin.org` or
`echo.websocket.org`, in the gate (M3.6) or locally. Then point the sample
at a server the script starts on the host (reached at `10.0.2.2`), with the
public URLs left as the default.

## How it was paid

> Filled when closing.
