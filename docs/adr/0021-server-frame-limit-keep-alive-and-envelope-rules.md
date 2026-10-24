---
id: ADR-0021
title: Server frame limit, keep-alive and envelope rules
status: Accepted
date: 2026-10-22
superseded_by:
---

# ADR-0021 — Server frame limit, keep-alive and envelope rules

## Context

Real devices (M1) reach the desktop over Wi-Fi. Until now the server read
frames of any size, never noticed a device that vanished without a TCP
close, and delivered every envelope after `Hello` whatever its channel or
`request_id` (DEBT-0008, DEBT-0018). Already decided, not reopened here:
one body is at most 1 MiB (ADR-0018); bodies carry `body_truncated` and
`body_size` (ADR-0020); a lagging collector stops socket reads (ADR-0009);
a protocol violation so far closes with 1002 (SDD 01).

Evidence, Ktor 3.6.0 sources (`ktor-websockets`, `ktor-server-websockets`
jars in the Gradle cache), checked on 2026-10-22:

- `WebSockets` options `maxFrameSize`, `pingPeriodMillis`, `timeoutMillis`.
- `readFrame(maxFrameSize, …)` rejects a frame from its header, before
  reading its payload, and the raw session closes with `TOO_BIG` (1009).
- The pinger sends a ping every period and, without the matching pong
  within the timeout, closes with `INTERNAL_ERROR` (1011) "Ping timeout";
  the client's `DefaultWebSocketSession` answers pings by itself.
- Pongs are read by the same incoming processor that hands data frames to
  the session; while it waits for a full session channel, no pong is read.

## Options

1. **Frame limit**: (a) exactly the body limit — a body at the limit with
   its URL and headers is refused; (b) body limit + 256 KiB for URL,
   headers and envelope; (c) unbounded — a single frame can take the
   desktop's memory.
2. **Keep-alive**: (a) Ktor's pinger, 15 s period, 15 s timeout — a gone
   device ends within ~30 s; (b) TCP keep-alive — OS defaults take hours;
   (c) application-level heartbeat envelopes — protocol change for what
   WebSocket already has.
3. **Bad envelopes** (a channel not in `HelloAck`; a known `network` type
   whose payload does not decode or has an empty `request_id`): (a) close
   with 1002, like a non-binary frame — loud, the session keeps what came
   before; (b) drop and count — the session survives, the SDK bug hides;
   (c) keep apart in the store under a synthetic id — the store learns
   protocol rules, and an event without id can never be linked.

## Decision

1(b): `Protocol.MAX_BODY_BYTES` (1 MiB) and `Protocol.MAX_FRAME_BYTES`
(+ 256 KiB), set as the server's `maxFrameSize`; larger frames close 1009.
2(a): ping every 15 s, 15 s timeout. 3(a): the server closes with 1002,
after delivering every earlier envelope. Unknown `network` types and other
accepted channels pass undecoded, so a newer SDK type is not a violation.

## Consequences

- `DeviceSession.envelopes` only carries accepted channels and `network`
  events with a request id; `TrafficState` needs no rule of its own
  (DEBT-0008 and DEBT-0018 paid).
- The server decodes each `network` payload once more than before.
- An SDK whose body limit exceeds `MAX_BODY_BYTES`, or whose URL and
  headers exceed 256 KiB, gets 1009 (DEBT-0021).
- A collector stalled longer than the ping timeout ends a healthy session
  with 1011 (DEBT-0022).
