---
id: ADR-0040
title: Swift transport on Network.framework, confined to one serial queue
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0040 — Swift transport on Network.framework, confined to one serial queue

## Context

PLAN-0003 M2.4 writes the Swift transport's lifecycle before its code
(M2.5, M2.6). It keeps SDD 04's states, bounds and buffer policy (ADR-0022,
ADR-0024); this ADR picks the socket API and the threading model. Already
decided, not reopened here: Network.framework (ADR-0003); every
`URLSession` of the process is captured after `start()` (ADR-0035 C.1); no
ATS key while the transport is Network.framework (ADR-0036 E); iOS 15
(ADR-0035 E); Swift 6 language mode (`sdk/ios/Package.swift`).

Evidence, checked on 2026-10-23 in the iOS 26.5 SDK
(`Network.swiftmodule/arm64e-apple-ios.swiftinterface`) and the Apple
documentation JSON (`developer.apple.com/tutorials/data/documentation/network/...`):

- `NWProtocolWebSocket` (iOS 13): `Options.autoReplyPing`,
  `Options.maximumMessageSize` ("to be received"), `Metadata(opcode:)` with
  `.ping` and `setPongHandler(_:handler:)`, `Metadata.closeCode`.
  `NWEndpoint.url(_:)` (iOS 13). `NWConnection` (iOS 12) has `cancel()`
  ("gracefully disconnects") and `forceCancel()`, and delivers every
  callback on the `DispatchQueue` passed to `start(queue:)`.
- `NWConnection.State.waiting`: "waiting for a network path change … These
  errors are not fatal."
- TN3179: a Bonjour operation without local network access waits with
  `kDNSServiceErr_PolicyDenied` (-65570); the simulator has no local network
  privacy.
- `DispatchSerialQueue` as an actor executor and `Mutex` (Synchronization)
  need iOS 17 and iOS 18; `NSLock` has no floor.

## Options

1. **Socket API**: (a) `URLSessionWebSocketTask` — goes through the
   `URLSession` capture the SDK itself installs (ADR-0035 C.1), so its own
   frames would show up as app traffic unless excluded, and on iOS 17+ an IP
   host needs `NSAllowsLocalNetworking`, which relaxes ATS for the app's own
   local traffic too (ADR-0036 E); (b) `NWConnection` with
   `NWProtocolWebSocket` — invisible to the capture, no ATS, explicit ping
   and close control.
2. **Threading**: (a) an `actor` client — every `await` is a reentrancy
   point between which `stop()` can run, and Network.framework still calls
   back on a queue, so each callback hops; a queue executor needs iOS 17;
   (b) one private serial `DispatchQueue` per client, the same queue given
   to `NWConnection`, `NWBrowser` and the timers, so every state change runs
   on it in order; the buffer behind an `NSLock`, the only state touched
   from the app's threads.
3. **`.waiting` while connecting**: (a) treated as a failure — the first
   connection on a device waits while the local network prompt is shown,
   so it would back off before the user answers; (b) not an exit — only
   `.ready`, `.failed`, `.cancelled` and the 10 s bound end connecting.

## Decision

1(b), 2(b), 3(b). The endpoint is `NWEndpoint.url(ws://host:port/path)`;
`maximumMessageSize` is `Protocol.MAX_FRAME_BYTES`; `autoReplyPing` is on
for the desktop's pings; the client sends its own ping every 15 s and
gives up after 30 s without a pong, as Ktor's `pingIntervalMillis * 2`
(ADR-0022). The client type is `@unchecked Sendable` with all its mutable
state confined to its queue.

## Consequences

- Nothing to exclude from the capture: the SDK's connection never touches
  `URLSession`.
- Ping, pong timeout and the connect bound are the SDK's own code, not a
  library setting as in Ktor; M2.5 tests them.
- The `stopped` exit on cleartext blocked (Android) has no iOS counterpart:
  ATS does not govern Network.framework.
- `@unchecked Sendable` moves the data-race check from the compiler to one
  rule (queue confinement), held by review and M2.5's tests.
