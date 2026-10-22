---
id: DEBT-0003
title: Desktop app ignores WebSocket frames
status: Open
date: 2026-10-21
trigger: The first SDK that emits WebSocketFrame lands (M1 sdk/android-okhttp or M2 iOS WebSocket tasks)
paid_date:
---

# DEBT-0003 — Desktop app ignores WebSocket frames

## What is missing

`desktop/app` builds its request list from `RequestStarted`,
`ResponseReceived` and `RequestFailed`. `WebSocketFrame` envelopes are
received and dropped by `TrafficState.received`: replaying the fixtures shows
two requests, and the `req-0003` frame appears nowhere in the UI.

## Why it was accepted

PLAN-0001 M0.5 asks for method, URL, status, duration, headers and bodies. No
SDK sends WebSocket traffic yet, so the shape of a frame view (timeline per
connection, per-frame payload format) would be designed against one fixture.

## Trigger

The first SDK that emits `WebSocketFrame` lands (M1 `sdk/android-okhttp` or
M2 iOS WebSocket tasks). Then frames attach to the exchange of their
`request_id` and the detail pane lists them.

## How it was paid

> Filled when closing.
