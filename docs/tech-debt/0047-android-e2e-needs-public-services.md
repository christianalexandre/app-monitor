---
id: DEBT-0047
title: The Android and iOS end to end depend on public services
status: Paid
date: 2026-10-23
trigger: The first `make e2e-android` or `make e2e-ios` failure caused by `httpbin.org` or `echo.websocket.org` (a gate run or a local one)
paid_date: 2026-10-24
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

Fired on 2026-10-24: release gate run 40093851446 on PR #132,
`e2e-android` logged `no ResponseReceived` for every `httpbin.org` and
`echo.websocket.org` call; the rerun passed with no code change. Paid by
PLAN-0005 M4.8a, before `make e2e-kmp` adds a third run on the same hosts.

## How it was paid

PLAN-0005 M4.8a, ADR-0074: `tools/e2e-target` serves the samples' paths
and a WebSocket echo on the host's loopback; `make e2e-android` and
`make e2e-ios` start it and pass its URL as the `target` launch parameter,
and fail when a request goes elsewhere. Both passed on 2026-10-24 with every
URL on `10.0.2.2:N` and `127.0.0.1:N`.
