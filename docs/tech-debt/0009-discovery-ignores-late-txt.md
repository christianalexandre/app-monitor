---
id: DEBT-0009
title: Fake client discovery keeps the resolve before the TXT record
status: Paid # Open | Paid | Dropped
date: 2026-10-22
trigger: The advertised path can differ from `Protocol.WEBSOCKET_PATH`, or M1 `sdk/core` implements discovery
paid_date: 2026-10-22
---

# DEBT-0009 — Fake client discovery keeps the resolve before the TXT record

## What is missing

`discover` completes on the first `serviceResolved`, which JmDNS can fire on
SRV/A before the TXT record arrives. The `path` property is then missing and
the default path is used; the later event that carries it is ignored.

## Why it was accepted

The desktop always advertises `Protocol.WEBSOCKET_PATH`, so the fallback is
the advertised value today. Waiting for TXT adds a second timeout to tune for
a case that does not exist.

## Trigger

The advertised path can differ from `Protocol.WEBSOCKET_PATH`, or M1
`sdk/core` implements discovery and needs the same rule.

## How it was paid

Paid in PLAN-0002 M1.6 (ADR-0024). `protocol` `DesktopSelector` keeps the
first desktop answered with an address and waits up to 500 ms for its TXT
record before using the default path; the fake client and `sdk/core` both
feed it. A TXT record later than the grace is still ignored, which only
matters if the desktop ever advertises another path.
