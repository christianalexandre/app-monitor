---
id: DEBT-0054
title: The Swift SDK's Bonjour probe is not checked on the simulator
status: Paid # Open | Paid | Dropped
date: 2026-10-24
trigger: The next PR that touches `sdk/ios/Sources/AppMonitor/BonjourBrowser.swift`, or the first end to end that runs the Swift SDK on the simulator without a manual host
paid_date: 2026-10-30
---

# DEBT-0054 — The Swift SDK's Bonjour probe is not checked on the simulator

## What is missing

The Swift SDK turns a Bonjour name into an address with a TCP probe to the
service endpoint (ADR-0042). Its Bonjour tests run on the macOS host and its
simulator runs (`make swift-sim`, `make e2e-ios`) use a manual host, so the
probe has never run on the simulator. The same probe written with
`nw_connection` in Kotlin/Native stayed in preparing there (ADR-0068). Symptom
if it is broken: a Swift app on the simulator without `host` never finds the
desktop by Bonjour and connects to `127.0.0.1` after 3 s, which hides it when
the desktop runs on the same Mac.

## Why it was accepted

M4.4 builds the Kotlin side; the Swift SDK is not in its scope, and the
simulator fallback keeps the Swift sample working on this Mac.

## Trigger

The next PR that touches `sdk/ios/Sources/AppMonitor/BonjourBrowser.swift`, or
the first end to end that runs the Swift SDK on the simulator without a
manual host.

## How it was paid

H4.5 (ADR-0162) runs `DiscoveryTests.swift`, `BonjourBrowserTests` included, in
the simulator-hosted tests of `make swift-sim`: the probe resolved an
advertised listener to an IPv4 address there, unlike the Kotlin/Native
`nw_connection` probe (ADR-0068). The fix in that PR also restored
`make swift-sim`, which no longer compiled since #309.
