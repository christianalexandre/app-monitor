---
id: ADR-0002
title: Desktop is the server; devices discover and connect
status: Accepted
date: 2026-10-21
superseded_by:
---

# ADR-0002 — Desktop is the server; devices discover and connect

## Context

The developer's computer and the device (physical, Android emulator or iOS
simulator) share a network; cross-network use is out of scope. The previous
iOS-only tool used Bonjour (`_appmonitor._tcp`). Two network facts matter:

- The Android emulator sits behind a NAT; the host is reachable at `10.0.2.2`
  and multicast from the host does not reach it
  ([Android emulator networking](https://developer.android.com/studio/run/emulator-networking)).
- The iOS simulator shares the Mac's network stack, so `localhost` reaches the
  desktop.

## Options

1. **Device is the server, desktop discovers devices** — desktop needs to scan;
   breaks behind the emulator NAT.
2. **Desktop is the server, devices discover it** — one listener, many clients;
   SDKs stay small.

## Decision

The desktop runs a Ktor WebSocket server and advertises it via mDNS (JmDNS).
SDKs try, in order: manually configured host → mDNS → `10.0.2.2` (Android
emulator) / `localhost` (iOS simulator). First connection from a new device
requires approval in the desktop UI.

## Consequences

- Zero configuration in the three common setups.
- iOS needs `NSLocalNetworkUsageDescription` and `NSBonjourServices` in debug
  Info.plist.
- Approval flow and masking of sensitive headers are needed before any public
  release (security, see `AGENTS.md`).
