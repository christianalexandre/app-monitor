---
id: DEBT-0031
title: Non-correctness findings of the first M1 milestone review
status: Open
date: 2026-10-23
trigger: The next PR that touches one of the files listed below
paid_date:
---

# DEBT-0031 — Non-correctness findings of the first M1 milestone review

## What is missing

Findings of the `code-review` of `03a2474..1896eb4` that are not wrong
results, crashes, hangs, leaks, races or privacy issues (ADR-0016):

- `sdk/android-okhttp/.../AppMonitorInterceptor.kt`: the KDoc offers
  `addNetworkInterceptor`, where the response body is captured before
  OkHttp's `BridgeInterceptor` decompresses it. The bytes are the wire's, but
  the desktop does not decode `Content-Encoding: gzip` and shows hex.
- `desktop/app/.../store/TrafficState.kt`: each `WebSocketFrame` copies the
  exchange's frame list and the exchange list, so ingestion costs grow with
  the square of a socket's frame count (a 10 Hz ticker for an hour).
- `sdk/core/.../MonitorClient.kt`: `connectFailureLogged` is never reset after
  a session reaches connected, so a later outage of the same client logs
  nothing.

## Why it was accepted

None of them gives the developer wrong data or breaks the app; each is a
contained change best made beside the next edit of its file instead of a
standalone PR.

## Trigger

The next PR that touches `AppMonitorInterceptor.kt`, `TrafficState.kt` or
`MonitorClient.kt` pays that file's item.

## How it was paid

> Filled when closing.
