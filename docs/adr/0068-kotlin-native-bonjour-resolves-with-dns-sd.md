---
id: ADR-0068
title: sdk/core on iOS browses with nw_browser and resolves with dns_sd, not a TCP probe
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0068 — sdk/core on iOS browses with nw_browser and resolves with dns_sd, not a TCP probe

## Context

PLAN-0005 M4.4 gives `sdk/core` on Kotlin/Native the mDNS source of SDD 04.
Already decided, not reopened here: the `DesktopSelector` rule and the 500 ms
TXT grace (ADR-0024), the simulator falling back to `127.0.0.1` after 3 s
(ADR-0002, SDD 06), IPv4 answers only, and how the Swift SDK turns a
Bonjour name into an address: a TCP probe to the service endpoint
(ADR-0042). The common `MdnsBrowser` takes answers that already carry a host.

Evidence, gathered on 2026-10-24 on this Mac (iOS simulator, Kotlin 2.4.21):

- Kotlin/Native ships `platform.Network` (`nw_browser_*`, `nw_connection_*`,
  `nw_txt_record_*`) and `dns_sd` in `platform.darwin` (`DNSServiceResolve`,
  `DNSServiceGetAddrInfo`, `DNSServiceSetDispatchQueue`), read with `klib
  dump-metadata` on the 2.4.21 platform klibs.
- In `iosSimulatorArm64Test`, `nw_browser` with TXT records listed a service
  advertised by an `nw_listener` in the same test and the desktop running on
  the LAN, each within 1 s. The ADR-0042 probe, an `nw_connection` to the
  result's endpoint, stayed in `nw_connection_state_preparing` for 15 s for
  both, with or without the IPv4 restriction and with a fresh
  `nw_endpoint_create_bonjour_service` endpoint. An `nw_connection` to
  `127.0.0.1:<listener port>` in the same process left preparing at once.
- `DNSServiceResolve` then `DNSServiceGetAddrInfo` (IPv4) on the browser's
  queue resolved the same service in 0.5 s, port and TXT path right
  (`BonjourBrowserTest`), and found the running desktop
  (`LiveServerTest.findsARunningDesktopByBonjour`).

## Options

1. **`nw_browser` for names and TXT, `dns_sd` for the address**: SRV to
   target and port with `DNSServiceResolve`, then the target's first IPv4
   address with `DNSServiceGetAddrInfo`, both on the session's dispatch
   queue. No connection to the desktop; C callbacks through a `StableRef`,
   one service ref per name to release on every exit.
2. **The ADR-0042 TCP probe, as the Swift SDK** — one design for both SDKs,
   but it never left preparing on the simulator (above).
3. **`dns_sd` for the browse too** (`DNSServiceBrowse`) — one API, but TXT
   updates through another query per name; `nw_browser` already gives them.
4. **`NSNetServiceBrowser`** — deprecated "Use nw_browser_t" (ADR-0042) and
   needs a run loop the client's coroutines do not have.

## Decision

Option 1. The port from `DNSServiceResolve` is in network byte order and is
swapped; the first IPv4 address with `kDNSServiceFlagsAdd` wins. A resolve
that fails is dropped and the name resolved again on its next result; every
resolve still running ends with the round.

## Consequences

- The two SDKs resolve differently: the desktop sees no probe connection from
  a Kotlin app.
- Whether the Swift probe also stalls on the simulator is not checked: its
  Bonjour tests run on the macOS host, its simulator runs use a manual host
  (DEBT-0054).
- On a physical iPhone the local network prompt for `dns_sd` and `nw_browser`
  stays unproven, as for CIO's sockets (DEBT-0052).
