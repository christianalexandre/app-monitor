---
id: ADR-0004
title: Layered capture coverage on iOS
status: Accepted
date: 2026-10-21
superseded_by:
---

# ADR-0004 — Layered capture coverage on iOS

## Context

The goal is to capture *everything* an iOS app sends, including gRPC traffic
from third-party SDKs that the previous tool missed. `URLProtocol` only sees
`URLSession` traffic, and not background sessions. SDKs built on gRPC-Core
open their own sockets and do TLS with a statically linked BoringSSL, so an
in-app library on a physical device cannot read their plaintext: iOS forbids
inline code patching, and symbol rebinding (fishhook) only reaches dynamically
linked symbols.

Which SDK the previous tool missed is still unverified — confirm with the
Instruments Network template before tuning layer 1 vs layer 3.

## Options

1. **In-app only** — simple, but cannot decode third-party gRPC.
2. **Proxy only** — sees everything, but needs a CA, breaks with pinning, and
   loses in-app context.
3. **Layered** — in-app capture where possible, desktop proxy for the rest.

## Decision

Layered coverage, each layer shipped as its own milestone:

1. **Full content, in-app**: all `URLSession` traffic (including sessions
   created before `start()`, uploads, WebSocket tasks; background sessions via
   delegate proxying).
2. **Full content, adapters**: interceptors for libraries the app owns
   (grpc-swift `ClientInterceptor`, Ktor plugin).
3. **Metadata for everything, in-app**: host, port, bytes, timing and errors
   of every connection via rebinding of dynamic symbols (`getaddrinfo`,
   `connect`, `nw_connection_*`). Shown in the desktop as opaque TLS
   connections.
4. **Full content for everything, desktop proxy**: optional MITM proxy mode in
   the desktop with HTTP/2 + gRPC decoding; CA installed automatically on
   simulators (`xcrun simctl keychain booted add-root-cert`), manually on
   devices. Does not work against certificate pinning.

## Consequences

- The desktop UI must show which layer produced each entry.
- Layer 3 uses symbol rebinding: debug builds only, and covered by tests on
  every iOS version in CI.
- Layer 4 makes the desktop a proxy too — a larger security surface, bound to
  the local network and off by default.
