---
id: DEBT-0005
title: LAN interface filter duplicated in server and fake client
status: Paid
date: 2026-10-21
trigger: The first change to either filter, or a JVM client of sdk/core needs mDNS
paid_date: 2026-10-31
---

# DEBT-0005 — LAN interface filter duplicated in server and fake client

## What is missing

`tools/fake-client` `Discovery.lanAddresses()` is a copy of
`MdnsAdvertiser.lanAddresses()` in `desktop/server`. Discovery only works when
both pick the same interfaces; a fix to one (excluding docker or `utun`, say)
silently drifts from the other.

## Why it was accepted

Found in the M0 milestone review. The fake client only depends on
`desktop/server` in tests; a shared JVM module for one 10-line function is
premature while `sdk/core` (M1) will need its own discovery and shows where the
shared code belongs.

Re-armed in PLAN-0002 M1.6 (ADR-0024): `sdk/core` discovers through
Android's `NsdManager`, which picks the interfaces itself, so it needs no
filter and is not the shared place. The JVM target has no mDNS.

## Trigger

The first change to either filter, or a JVM client of `sdk/core` needs mDNS
— then the filter moves to one shared place.

## How it was paid

Paid by PLAN-0015 H5.2f (`fix/lan-filter-drops-tunnels`, ADR-0164). The
filter is `LanAddresses` in `desktop/server`: the advertiser, the fake
client's discovery and `desktop/mcp`'s `bindsLoopbackOnly` call it, and
`LanAddressesTest` checks it drops a tunnel's flags. The fake client's
copy had drifted already: it kept point-to-point interfaces, which the
advertiser dropped since #109.
