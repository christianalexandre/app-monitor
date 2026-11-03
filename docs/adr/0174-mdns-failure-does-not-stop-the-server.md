---
id: ADR-0174
title: An mDNS failure does not stop the server
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0174 — An mDNS failure does not stop the server

## Context

ADR-0009 publishes the service with one JmDNS instance per LAN address. Until
now `MdnsAdvertiser.start` was all or nothing and `AppMonitorServer.start`
treated its failure as fatal, so a bridge that rejected JmDNS left the window
at "Server failed" with no port open, even for a device with a manual host,
which SDD 01 puts first in its discovery order. With no LAN address it did the
opposite: "Listening", with nothing published (DEBT-0007).

Two more defects sat in the same class, measured on this machine (en0 on the
LAN, en9 a link-local USB interface) with JmDNS 3.6.3:

- `JmDNS.close` took 3014 ms on en9 and 2016 ms on en0 with a service
  registered. Closed in turn, that is the whole 5 s `STOP_TIMEOUT`, where the
  shutdown hook gives up, before the 1 s engine stop (DEBT-0015). The
  debt said no such stop had been seen; it had only not been measured.
- `JmDNS.create(address)` takes its host name from the address's reverse
  lookup. `make server` printed `christians-macbook-pro.local/169.254.40.197`
  and `192.168.10.249/192.168.10.249` on the same machine, so whether two
  instances shared a host depended on the resolver (DEBT-0012).
  `AppMonitorServerTest` already gives its browser a distinct name for this reason.

## Options

1. **Keep all or nothing, retry without the failing address.** Starts every
   instance twice on a failure, and still needs a state for "some failed".
2. **Skip what fails, report it, always listen.** The port is what a device
   with a manual host needs; advertising is one way of finding it.
3. **`JmmDNS` instead of one instance per address.** It tracks interfaces
   itself, but it picks them without `LanAddresses` (ADR-0164), which is the
   filter the fake client must share.

For the close: closing in parallel makes the time the slowest address's;
stopping the engine first would make devices see the port go before the
goodbye, and a bound alone would cut every stop with two addresses.

## Decision

Option 2. An address whose registration throws an `Exception` is skipped;
`AppMonitorServer.advertisingProblem` says which failed, or that no address
took it and devices need the machine's address. The window's status and the
headless server's output carry it. An `Error` still closes what registered and
fails `start`, and so does a failure listing the interfaces, which is no
single interface's doing.

Each instance is `JmDNS.create(address, "appmonitor-<address with dashes>")`.

`MdnsAdvertiser.close` closes every instance at once and waits at most 3.5 s,
which with the engine's 1 s stays under `STOP_TIMEOUT`. An instance still
closing is reported as a failure of `stop` ("Server stop failed") and goes on
in the background, so the port is free within the bound either way.

## Consequences

- A desktop with no usable LAN address starts, and says so, instead of
  failing or staying silent. Measured: `make server`'s stop 3.06 s with two
  addresses, against more than `STOP_TIMEOUT` closing in turn.
- The service can still be renamed: browsing while en0 and en9 advertise shows
  `AppMonitor` and `AppMonitor (2)` on en0, with JmDNS's default host names and
  with these alike, because en9's instance is heard on en0. ADR-0024 accepted a
  rename, as the SDK has no name option. Which address a device tries first
  when `AppMonitor` is the link-local one is DEBT-0123.
