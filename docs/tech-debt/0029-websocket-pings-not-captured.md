---
id: DEBT-0029
title: WebSocket ping and pong frames are not captured
status: Open
date: 2026-10-22
trigger: OkHttp exposes ping or pong frames to a listener, or a developer asks to see WebSocket keep-alive traffic
paid_date:
---

# DEBT-0029 — WebSocket ping and pong frames are not captured

## What is missing

The desktop lists text, binary and close frames of a WebSocket, never ping
or pong: an app that relies on OkHttp's `pingInterval` shows no keep-alive
traffic, and a connection that dies of a missed pong shows only its failure.

## Why it was accepted

OkHttp 5.3.2 answers pings and sends its own inside `RealWebSocket`;
`WebSocketListener` has only `onOpen`, `onMessage` (text, bytes),
`onClosing`, `onClosed` and `onFailure` (`javap okhttp3.WebSocketListener`).
Seeing them would need OkHttp internals or a network-level hook. Split from
DEBT-0025, whose truncation half was paid by ADR-0028. The iOS SDK has the
same gap: `URLSessionWebSocketTask` answers pings itself (ADR-0045).

## Trigger

OkHttp exposes ping or pong frames to a listener, or a developer asks to see
WebSocket keep-alive traffic.

## How it was paid

> Filled when closing.
