---
id: ADR-0009
title: Desktop server sessions, endpoint and mDNS advertisement
status: Accepted
date: 2026-10-21
superseded_by:
---

# ADR-0009 — Desktop server sessions, endpoint and mDNS advertisement

## Context

ADR-0002 made the desktop a Ktor WebSocket server advertised over mDNS with
JmDNS. PLAN-0001 M0.3 builds it in `desktop/server`. Open points: which Ktor
engine, where devices connect (port, path), how sessions reach the rest of
the desktop, and which JmDNS version.

Evidence, checked on 2026-10-21:

- Maven Central: Ktor 3.6.0 (2026-09-16, built against coroutines 1.11.0);
  JmDNS 3.6.4 (2026-10-10) and 3.6.3 (2025-12-27), Apache-2.0.
- JmDNS 3.6.4 `JmDNSImpl.getMulticastBindAddress` binds the receive socket
  to the host's unicast address on every OS except Windows/JDK < 17; 3.6.3
  binds the wildcard on macOS and Linux (sources jars). On macOS a socket bound
  to a unicast address does not receive multicast. Measured: a raw mDNS PTR
  query on 224.0.0.251 got no answer from a 3.6.4 advertiser and an answer
  from 3.6.3 and from macOS `dns-sd -R`. With 3.6.4 the service is visible
  only through its initial announcements, not to a device that browses later.
- The Android emulator reaches the host only at `10.0.2.2` without multicast
  (ADR-0002), so the SDK needs a port it can guess.

## Options

1. **Engine: Netty vs CIO** — Netty is the most tuned; CIO is pure Kotlin,
   fewer transitive dependencies, enough for a handful of local devices.
2. **Sessions as `StateFlow<List<…>>`** — UI-friendly but conflated: a device
   that connects and leaves between two collections is lost with its traffic.
3. **Sessions as a `Flow` backed by a channel** — every session delivered once,
   in order; the consumer builds its own state.
4. **JmDNS 3.6.4** (latest) vs **3.6.3** (previous) — see Context.

## Decision

Ktor **CIO** engine, Ktor 3.6.0. Fixed default port **47474** and path
**`/v1/ws`**, both in `appmonitor.protocol.Protocol` next to the mDNS type;
the TXT record carries `v` (protocol version) and `path`. `AppMonitorServer`
exposes `sessions: Flow<DeviceSession>` (option 3); each `DeviceSession`
exposes `envelopes: Flow<Envelope>` backed by a bounded channel, so a slow
collector stops reading the socket and TCP back-pressures the device instead of
growing memory. Both flows have a single collector, the desktop ingest. JmDNS
pinned to **3.6.3** (DEBT-0001), one instance per up, multicast-capable LAN
IPv4 address.

## Consequences

- The server never drops envelopes; a stalled collector stalls the device.
- Devices that cannot use mDNS still connect with host + 47474.
- The JmDNS pin is guarded by the mDNS integration test, which fails on 3.6.4.
