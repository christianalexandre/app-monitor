---
id: ADR-0022
title: SDK connection lifecycle, offline buffer and backoff
status: Accepted
date: 2026-10-22
superseded_by:
---

# ADR-0022 — SDK connection lifecycle, offline buffer and backoff

## Context

`sdk/core` (PLAN-0002 M1.5, M1.6) keeps one connection to the desktop for
as long as the app runs, across Wi-Fi drops, desktop restarts and the
approval dialog. Its states are written in SDD 04 before the code. Already
decided, not reopened here:

- `AppMonitor.start(context, options)` / `stop()`, called by the app;
  `enabled = false` makes `start()` a no-op (ADR-0017).
- Discovery order manual host → mDNS → `10.0.2.2` (ADR-0002).
- The desktop answers `Hello` with `HelloAck` only after approval, waits for
  it without a timeout and rejects with 1008; the SDK holds events in a ring
  buffer meanwhile (ADR-0018).
- The desktop closes 1002 on a protocol violation, 1009 on a frame over
  `Protocol.MAX_FRAME_BYTES` (1.25 MiB), 1011 on a missed pong; it pings
  every 15 s (ADR-0021).
- The protocol has no acknowledgement of envelopes (`protocol/proto/`).

Evidence, Ktor 3.6.0 client sources
(`ktor-client-core/common/src/io/ktor/client/plugins/websocket/WebSockets.kt`
at tag `3.6.0`), checked on 2026-10-22: the client `WebSockets` plugin has
`pingIntervalMillis` (disabled by default) and `maxFrameSize`; its session
uses a pong timeout of `pingIntervalMillis * 2`, not configurable.

## Options

1. **Buffer bound**: (a) by count only — 1000 events at the 1 MiB body
   limit is 1 GiB of an app's heap; (b) by encoded bytes and by count,
   whichever is reached first, dropping the oldest whole envelope; (c) on
   disk — survives a process death, but writes captured traffic to the
   app's storage (a privacy change) for a debug tool.
2. **When an envelope leaves the buffer**: (a) when it is written to the
   socket (at most once) — an abrupt drop loses what was in flight, and an
   envelope the desktop rejects is not resent; (b) when the desktop
   acknowledges it (at least once) — needs a protocol message, and a
   rejected envelope is resent forever unless the SDK guesses which one it
   was.
3. **Retry**: (a) fixed delay — hammers a desktop that is down, or slow to
   recover; (b) exponential from 1 s, doubled up to 30 s, ±20 % jitter,
   reset once a session reaches connected; terminal failures (cleartext
   blocked) stop instead of retrying.
4. **Liveness on the device**: (a) none — a desktop gone from Wi-Fi leaves
   the SDK writing into a dead TCP buffer until the OS gives up; (b) the
   client pinger, every 15 s, like the server.
5. **`start()` after `stop()`**: (a) refused, single use like
   `AppMonitorServer`; (b) starts a fresh client — an app may toggle the
   monitor from a debug menu.

## Decision

1(b): at most 4096 envelopes and 8 MiB encoded, in memory only; an envelope
larger than `Protocol.MAX_FRAME_BYTES` is dropped before it enters the
buffer, since the desktop would close 1009 on it. 2(a): at most once.
3(b). 4(b): `pingIntervalMillis` 15 s, so a dead desktop is noticed within
45 s. 5(b): `start()` after `stop()` builds a new client with an empty
buffer; `start()` while running is ignored.

## Consequences

- Buffer overflow and in-flight loss are silent on the desktop (DEBT-0023).
- An oversized envelope is lost instead of ending the session; DEBT-0021
  stays open for bounding it where it is built (M1.7).
- A rejected device reconnects at most every 30 s, and each attempt is
  closed 1008 at once while the desktop remembers the rejection.
