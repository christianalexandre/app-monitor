---
id: DEBT-0046
title: The mDNS round trip test is skipped on the hosted macOS runner
status: Open
date: 2026-10-23
trigger: The runner image grants local network access (actions/runner-images#10924), or the next PR that touches `release-gate.yml` to add a job that needs Bonjour on macOS (M3.8)
paid_date:
---

# DEBT-0046 — The mDNS round trip test is skipped on the hosted macOS runner

## What is missing

The release gate's `desktop (macos-latest)` job runs `:desktop:server:test`
with `APPMONITOR_NO_LOCAL_NETWORK=1`, which excludes
`AppMonitorServerTest.advertised service resolves over mdns`. On macOS the
real mDNS advertisement is only checked on the owner's Mac (`make build`).
ubuntu and windows run it in the gate.

## Why it was accepted

Without the exclusion the test times out on every macOS run (runs
40044650705, rerun included). The runner's network is normal (`en0`
`192.168.64.11`, `224.0.0.251` joined, firewall off, diagnosed in run
40045596596), but macOS 15 denies local network access to processes the
runner launches, and Apple DTS says the permission cannot be granted by a
profile, MDM or `tccutil`
([Apple forums 770473](https://developer.apple.com/forums/thread/770473));
OpenJDK skips its multicast tests on the same runner for the same reason
([JMC-8437](https://bugs.openjdk.org/browse/JMC-8437)). The advertiser code
is the same JVM code the other two OSes test.

## Trigger

The runner image grants local network access
(actions/runner-images#10924), or the next PR that touches
`release-gate.yml` to add a job that needs Bonjour on macOS: M3.8's iOS end
to end will hit the same wall if the simulator discovers the desktop by
Bonjour instead of a direct host.

## How it was paid

> Filled when closing.
