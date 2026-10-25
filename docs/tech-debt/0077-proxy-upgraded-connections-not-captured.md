---
id: DEBT-0077
title: The proxy does not capture what crosses an upgraded connection
status: Open
date: 2026-10-25
trigger: "A developer's WebSocket through the proxy shown with no frames, or the next PR that changes how `desktop/proxy/src/main/kotlin/appmonitor/desktop/proxy/ClientHandler.kt` handles a `101`"
paid_date:
---

# DEBT-0077 — The proxy does not capture what crosses an upgraded connection

## What is missing

A `101 Switching Protocols` through the proxy is stored as an exchange
with status 101, then both sides go over to a raw byte relay (SDD 11).
A WebSocket opened that way has no frames in the window or in MCP,
where an SDK's would (ADR-0028).

## Why it was accepted

ADR-0101 is about the traffic layer 1 cannot see, gRPC first; a
simulator app's `URLSession` WebSockets are already captured with their
frames by the SDK, and B.1 sends only gRPC-Core through the proxy. Decoding
frames means a WebSocket codec on each side and `WebSocketFrame` events
per direction: M8.6 touches the same handler for HTTP/2.

## Trigger

A developer's WebSocket through the proxy shown with no frames, or the next PR that changes how `desktop/proxy/src/main/kotlin/appmonitor/desktop/proxy/ClientHandler.kt` handles a `101`.

Fired by PLAN-0009 M8.6, which touches `ClientHandler.kt` only to hand a
tunnel that opens with the HTTP/2 preface to `Http2Relay.kt`; the `101`
path is unchanged. Re-triggered on the milestone review, which weighs it with the
rest of the plan's open items.

Fired by the PLAN-0009 milestone review. Not paid: no developer has reported a WebSocket through the proxy; an app's WebSockets reach the desktop through the SDKs' capture. Re-triggered on that event or on a change to the `101` path.

## How it was paid

> Filled when closing.
