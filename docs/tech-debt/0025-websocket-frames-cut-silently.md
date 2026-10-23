---
id: DEBT-0025
title: WebSocket frame payloads are cut without a marker, pings unseen
status: Paid
date: 2026-10-22
trigger: The desktop renders WebSocket frames (M1.10), or a frame over `maxBodyBytes` is reported shown whole
paid_date: 2026-10-22
---

# DEBT-0025 — WebSocket frame payloads are cut without a marker, pings unseen

## What is missing

`AppMonitorWebSocketFactory` keeps at most `maxBodyBytes` of a frame's
payload, but `WebSocketFrame` has no truncation flag or original size, so
the desktop cannot tell a cut frame from a whole one. Ping and pong frames
are handled inside OkHttp and never reach a listener, so they are not
captured.

## Why it was accepted

No UI shows frames yet (DEBT-0003). Adding `payload_truncated` and
`payload_size` is a protocol change best made with the UI that renders them.

## Trigger

The desktop renders WebSocket frames (M1.10), or a frame over
`maxBodyBytes` is reported shown whole.

## How it was paid

PLAN-0002 M1.10: `WebSocketFrame` gained `payload_truncated` and
`payload_size` (ADR-0028), set by `AppMonitorWebSocketFactory` and shown by
the desktop. Ping and pong remain unseen: moved to DEBT-0029.
