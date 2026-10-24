---
id: ADR-0042
title: A Bonjour result's address comes from a TCP probe to its service
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0042 — A Bonjour result's address comes from a TCP probe to its service

## Context

PLAN-0003 M2.6 writes the Swift discovery. SDD 06 leaves one gap: how an
`NWBrowser` result, which carries a service name and its TXT record but no
address, becomes the `host` and `port` the transport connects to. Already
decided, not reopened here: `NWBrowser` with `bonjourWithTXTRecord`, the
`DesktopSelector` rule (first desktop answered with an IPv4 address, names
compared case-insensitively, 500 ms TXT grace), the simulator falls back to
`127.0.0.1` after 3 s (ADR-0024, SDD 06); the transport connects to
`NWEndpoint.url(ws://host:port/path)` (ADR-0040).

Evidence, checked on 2026-10-23 in the iOS 26.5 SDK and on macOS 26:

- `NWBrowser.Result` has `endpoint` (`.service(name:type:domain:interface:)`),
  `interfaces` and `metadata` (`.bonjour(NWTXTRecord)`); TXT updates come as
  `.changed(old:new:flags:)` with `.metadataChanged`
  (`Network.swiftmodule/arm64e-apple-ios.swiftinterface`).
- `NWProtocolWebSocket.Options` has `autoReplyPing`, `maximumMessageSize`,
  `skipHandshake`, additional headers and subprotocols, and no request path:
  the TXT `path` only reaches the server through a `.url` endpoint.
- `NSNetServiceBrowser` and `NSNetService` are `API_DEPRECATED("Use
  nw_browser_t …")` / `("Use nw_connection_t …")`, `API_TO_BE_DEPRECATED`
  (`Foundation.framework/Headers/NSNetServices.h`).
- A prototype on this Mac: a service advertised by `NWListener` was browsed
  in about 1 s; an `NWConnection` with `NWParameters.tcp` restricted to
  IPv4, to the result's `.service` endpoint, was `.ready` 16 ms later with
  `currentPath.remoteEndpoint` = `192.168.3.221%en0:65327`. The listener saw
  one connection, closed at once. Against `make server`, `MonitorClient`
  reached connected through this path in 2.07 s and the server logged
  nothing for the probe.

## Options

1. **TCP probe**: per new name, an IPv4-only `NWConnection` to the result's
   `.service` endpoint; at `.ready`, read `currentPath.remoteEndpoint`,
   cancel. Network.framework resolves SRV and A itself, on the same queue.
   Costs one empty TCP connection to the desktop per name per round; the
   probe needs local network access, as any Bonjour operation does.
2. **`DNSServiceResolve` then `DNSServiceGetAddrInfo`** (`dns_sd`): no
   connection, but C callbacks with an `Unmanaged` context pointer, two
   service refs to release on every exit, and a second API next to
   `NWBrowser`.
3. **`NetService.resolve(withTimeout:)`**: to-be-deprecated, needs a run
   loop, and duplicates the browse `NWBrowser` already does.
4. **Connect the WebSocket to the `.service` endpoint directly**: no
   resolve step, but no way to send the TXT `path` (above), so a desktop
   advertising a path other than `/v1/ws` could not be reached.

## Decision

Option 1. The host is the IPv4 address formatted without its interface
scope (`192.168.3.221`, not `…%en0`). A probe that fails is dropped and the
name probed again on its next result; one still waiting ends with the round.

## Consequences

- The desktop sees one TCP connection with no bytes before each session
  found by Bonjour; `make server` logged nothing for it.
- An IPv6-only desktop is not found: SDD 06 keeps IPv4 answers only.
- `BonjourBrowser` is tested on the macOS host against an `NWListener`
  service; local network privacy, and whether the probe waits for the prompt
  on a device, is proven in M2.13 only (TN3179).
