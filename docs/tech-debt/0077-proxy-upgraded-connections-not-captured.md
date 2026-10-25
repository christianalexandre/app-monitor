---
id: DEBT-0077
title: The proxy does not capture what crosses an upgraded connection
status: Open
date: 2026-10-25
trigger: The next PR that changes `desktop/proxy/src/main/kotlin/appmonitor/desktop/proxy/ClientHandler.kt`, or a developer's WebSocket through the proxy shown with no frames, whichever comes first
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

The next PR that changes
`desktop/proxy/src/main/kotlin/appmonitor/desktop/proxy/ClientHandler.kt`
(M8.6), or a developer's WebSocket through the proxy shown with no
frames, whichever comes first.

## How it was paid

> Filled when closing.
