---
id: DEBT-0041
title: The iOS end to end has run on the simulator only, never on a physical iPhone
status: Paid
date: 2026-10-23
trigger: A physical iPhone is connected to the Mac that runs the loop, or M10's first tagged release, whichever comes first
paid_date: 2026-11-03
---

# DEBT-0041 — The iOS end to end has run on the simulator only, never on a physical iPhone

## What is missing

PLAN-0003 M2.13 proves sample app → desktop app on the iPhone simulator
only. The simulator runs on the Mac's own network stack, so the path a real
device takes is not exercised: Bonjour discovery over Wi-Fi from another
host, the local network permission prompt with the Info.plist keys of
ADR-0036 and M2.11, and signing the sample for a device.

## Why it was accepted

No physical iPhone is available (loop report `.loop/20261023-181432-8.md`:
`xcrun devicectl list devices` printed "No devices found"), and the owner
asked to go on with simulators. The Android side ran on a real device in
PLAN-0002 M1.12, and the Swift discovery was tested against a real Bonjour
round on the Mac (M2.6).

## Trigger

A physical iPhone is connected to the Mac that runs the loop, or M10's
first tagged release, whichever comes first. Paying it is the device
half of the old M2.13 Accept: a screenshot in `docs/screenshots/` of the
desktop showing the sample's traffic from the iPhone, found by mDNS.

Re-triggered on 2026-10-24 by PLAN-0004 M3.12: the first release PR fired
it, but `xcrun devicectl list devices` still printed "No devices found",
and that PR proves the gate, it publishes nothing; the hosted runner has no
iPhone either (ADR-0052). M10's tag is the first release an app installs.

## How it was paid

Paid by the H4 field test (2026-10-30, PLAN-0014's *Outcome*), PLAN-0015
H5.6. The field test app's `Dev` on an iPhone 8, iOS 16.7.16, on Wi-Fi: signed for the
device, it found the desktop by mDNS from another host once Local Network
was on, and 25 calls were captured, each answered. That is the path this
debt named. It found two bugs the simulator hid (#319, #321).

Left: it was the owner's app by hand, not `samples/ios`, so there is no
screenshot in `docs/screenshots/`, and nothing repeats it. The owner chose
not to run an end to end on a physical iPhone (2026-10-30).
