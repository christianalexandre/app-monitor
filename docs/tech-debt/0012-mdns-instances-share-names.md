---
id: DEBT-0012
title: Per-address mDNS instances share a host and service name
status: Paid
date: 2026-10-22
trigger: A desktop with two addresses on one LAN is seen renamed or flapping, or an SDK option filters by service name
paid_date: 2026-11-03
---

# DEBT-0012 — Per-address mDNS instances share a host and service name

## What is missing

`MdnsAdvertiser` runs one `JmDNS.create(address)` per LAN address, each with
the default host name and the same service name. Two addresses on one
segment (Wi-Fi and Ethernet on the same subnet, or two addresses on one NIC)
let the instances see each other's probes: one service can be renamed
`AppMonitor (2)` or the host record can flap, and a client that filters by
name or resolves the address may pick the wrong one.

## Why it was accepted

Found in the fifth M0 review. Not observed on the development machine, which
has one LAN address. The fix (one instance per interface, distinct host
names, or `JmmDNS`) is a design choice that also touches DEBT-0007, better
made with real devices discovering the desktop in M1.

Re-armed in PLAN-0002 M1.6 (ADR-0024): the SDK has no service name option;
it keeps the first desktop answered, and a renamed `AppMonitor (2)` of the
same desktop leads to the same server.

## Trigger

A desktop with two addresses on one LAN is seen renamed or flapping, or an
SDK option filters by service name.

## How it was paid

Paid by PLAN-0016 H6.1 (ADR-0174). One host name per address,
`appmonitor-<address>`, instead of what the reverse lookup answers. The service
name can still be renamed `AppMonitor (2)` when one instance hears another; that
was so with the default names too (measured), and DEBT-0123 tracks where it matters.
