---
id: ADR-0164
title: One LAN address filter, in `desktop/server`, without tunnels
status: Accepted
date: 2026-10-31
superseded_by:
---

# ADR-0164 — One LAN address filter, in `desktop/server`, without tunnels

## Context

Three places picked "the machine's LAN addresses" with their own filter:

- `MdnsAdvertiser.lanAddresses()` in `desktop/server`: up, multicast,
  not loopback, not virtual, not point-to-point (the last added in #109).
- `lanAddresses()` in `tools/fake-client`'s `Discovery.kt`: the same
  without the point-to-point check (DEBT-0005: the copies drift).
- `LifecycleTest.bindsLoopbackOnly` in `desktop/mcp`: the first up,
  non-loopback IPv4 address, tunnels included.

On the owner's Mac with FortiClient up (2026-10-30), the tunnel is `utun5`,
`UP,POINTOPOINT,RUNNING,MULTICAST`, with its own IPv4 address. The MCP test
connected to that address and got `SocketTimeoutException` instead of the
`ConnectException` a loopback-only server gives on a LAN address, so
`make check` failed there, on `dev` too (DEBT-0036). On this Mac today
`ifconfig` lists `utun0` to `utun6` with the same flags and no IPv4
address, and `en0` as the only LAN IPv4 address.

`tools/fake-client` already depends on `desktop/server` in its tests, and
`desktop/mcp`'s tests depend on `tools/fake-client`.

## Options

1. **The filter in `desktop/server`, public** (`LanAddresses`); the fake
   client depends on `desktop/server` in `main`, `desktop/mcp` in tests.
   One definition, no new module; the fake client's runtime classpath gains
   Ktor server and `desktop/proxy`, which a development CLI does not mind.
2. **A new JVM module for one function.** Cleanest graph, but a module, a
   build file and a code map row for ten lines, which DEBT-0005 already
   called premature.
3. **Keep the copies, add the point-to-point check to each.** Fixes today's
   VPN case and leaves the drift DEBT-0005 is about.

## Decision

Option 1. `LanAddresses.current()` returns the IPv4 addresses of interfaces
that are up, multicast-capable, and neither loopback, virtual nor
point-to-point; the desktop advertises on them, the fake client browses on
them, and `bindsLoopbackOnly` connects to the first of them. The predicate
is `LanAddresses.isLan` over the interface's flags, tested with a tunnel's.

## Consequences

- A filter change reaches the advertiser, the fake client and the MCP test
  together; DEBT-0005 is paid.
- A VPN tunnel no longer gives `bindsLoopbackOnly` an address that times
  out; on a machine with only tunnels, the test checks the loopback half
  and says so, as it did on a machine with no LAN address.
- `tools/fake-client` builds after `desktop/server`.
