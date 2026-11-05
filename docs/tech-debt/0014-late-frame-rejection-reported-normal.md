---
id: DEBT-0014
title: A rejection of a late frame reaches the client as a normal close
status: Paid
date: 2026-10-22
trigger: When an SDK or a test needs to know the desktop rejected one of the last frames it sent before closing
paid_date: 2026-10-31
---

# DEBT-0014 — A rejection of a late frame reaches the client as a normal close

## What is missing

Ktor's `DefaultWebSocketSession` answers a peer's close as soon as its
incoming processor reads it (`DefaultWebSocketSession.kt`, `is Frame.Close`
branch, Ktor 3.6.0), while the desktop's handler may still hold earlier frames
in its buffer. If one of them is rejected, the desktop's `PROTOCOL_ERROR` close
is dropped and the client sees `NORMAL`. Symptom: `make fake-traffic` exits 0
although the desktop logged a rejected frame near the end of the session.

## Why it was accepted

The fake client only sends well-formed fixtures, and the desktop rejects only
non-binary frames, so the case does not happen today. A rejection while the
client is still sending is reported (M0.R18). Fixing it needs an application
level close handshake or a session without Ktor's automatic close echo.

## Trigger

When an SDK or a test needs to know the desktop rejected one of the last frames
it sent before closing.

## How it was paid

The desktop reads device frames over a raw Ktor session (ADR-0167,
PLAN-0015 H5.3): its `DeviceConnection` queues the device's close after the
frames before it, and answers it only once the handler has taken them, with
the handler's 1002 when it rejected one. Reproduced first: 500 envelopes, a
rejected one and the close, collected 300 ms later, got 1000 from the old
server, 1002 from the new (`ServerHardeningTest`). What is left: a device's
close now waits for a lagging collector; the SDKs bound that wait at 1 s.
