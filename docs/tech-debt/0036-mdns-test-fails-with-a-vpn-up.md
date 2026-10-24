---
id: DEBT-0036
title: The desktop's mDNS test fails while a VPN interface is up
status: Open
date: 2026-10-23
trigger: The next PR that changes either LAN interface filter (DEBT-0005), or a device fails to find the desktop while the Mac has a VPN up
paid_date:
---

# DEBT-0036 — The desktop's mDNS test fails while a VPN interface is up

## What is missing

`AppMonitorServerTest > advertised service resolves over mdns` times out
after 10 s on a Mac with a VPN up (`utun6`, `POINTOPOINT,MULTICAST`,
`10.0.0.5`), on a clean `origin/dev` at `b439832` too, so `make check`
fails there. `make server` logs `mDNS on [10.0.0.5/…, 192.168.1.20/…]`:
`lanAddresses()` keeps the tunnel, because it filters on `isVirtual` and
`supportsMulticast()` only. That the tunnel is what breaks the resolve is
likely, not isolated.

## Why it was accepted

Found in PLAN-0003 M2.6, which touches no Kotlin. The fix (drop
point-to-point interfaces) belongs in the one shared filter DEBT-0005 asks
for, not in a Swift PR. The Swift `BonjourBrowser` found the same server
through Network.framework in 2.07 s with the VPN up.

## Trigger

The next PR that changes either LAN interface filter (DEBT-0005), or a
device fails to find the desktop while the Mac has a VPN up.

## How it was paid

> Filled when closing.
