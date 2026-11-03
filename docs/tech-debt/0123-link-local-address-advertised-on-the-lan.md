---
id: DEBT-0123
title: A link-local interface's instance is heard on the LAN
status: Open # Open | Paid | Dropped
date: 2026-11-03
trigger: "A device on the LAN fails to connect by discovery while the desktop also has a link-local interface up (a phone on USB), or a change to `desktop/server/src/main/kotlin/appmonitor/desktop/server/LanAddresses.kt`"
paid_date:
---

# DEBT-0123 — A link-local interface's instance is heard on the LAN

## What is missing

With a phone on USB (`en9`, `169.254.40.197`) and Wi-Fi (`en0`), `dns-sd -B` on
`en0` lists `AppMonitor`, whose SRV is the link-local address's host, and
`AppMonitor (2)`, the LAN address's (ADR-0174, measured with JmDNS's default host
names too). The SDKs keep the first desktop answered with an address (ADR-0024),
so a phone on the Wi-Fi could be given an address it cannot reach. The symptom
would be a device retrying a `169.254` address while the desktop shows nothing.

## Why it was accepted

No device has been seen failing this way; the field test ran with this setup.
`LanAddresses` cannot just drop link-local: that address is how a phone on the USB
link finds the desktop (the comment in `AppMonitorServerTest`), and whether SDKs
should fall through to the next name is a separate choice. It wants a real phone
on the Wi-Fi, which the loop does not have.

## Trigger

A device on the LAN fails to connect by discovery while the desktop also has a
link-local interface up, or a change to
`desktop/server/src/main/kotlin/appmonitor/desktop/server/LanAddresses.kt`.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
