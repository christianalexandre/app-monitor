---
id: DEBT-0011
title: Fake client discovery blocks the JmDNS listener and matches names by case
status: Paid # Open | Paid | Dropped
date: 2026-10-22
trigger: More than one `_appmonitor._tcp` service is seen on one LAN, or M1 `sdk/core` implements discovery
paid_date: 2026-10-22
---

# DEBT-0011 — Fake client discovery blocks the JmDNS listener and matches names by case

## What is missing

`discover` calls `requestServiceInfo(type, name)` from `serviceAdded`. In
JmDNS 3.6.3 that overload waits up to `SERVICE_INFO_TIMEOUT` for the answer
(`JmDNSImpl.java` lines 877–903, Maven Central sources), on the listener
thread, so a stale service delays the next one past `--timeout` and discovery
reports "No desktop found". `--name` compares instance names with `!=`,
while DNS-SD names are case-insensitive, so `--name appmonitor` never matches
`AppMonitor`.

## Why it was accepted

Found in the fifth M0 review. The fake client is a dev tool on a LAN with one
desktop, where neither case shows. M1 `sdk/core` writes the discovery that
real devices use, and DEBT-0009 is in the same function.

## Trigger

More than one `_appmonitor._tcp` service is seen on one LAN while using the
fake client, or M1 `sdk/core` implements discovery and needs the same rules.

## How it was paid

Paid in PLAN-0002 M1.6 (ADR-0024). `requestServiceInfo` runs on an IO
coroutine per service, so a stale one no longer holds the listener, and
`DesktopSelector` compares names case-insensitively. `DiscoveryTest` pins
both. On Android, `NsdBrowser` serializes `resolveService` below API 34,
where NsdManager allows one resolve at a time.
