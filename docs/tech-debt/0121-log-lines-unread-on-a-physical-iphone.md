---
id: DEBT-0121
title: The SDK's public log lines not read on a physical iPhone
status: Open # Open | Paid | Dropped
date: 2026-11-03
trigger: "The next time an iPhone is connected to run the SDK on it (the owner's field test, or a device end to end)"
paid_date:
---

# DEBT-0121 — The SDK's public log lines not read on a physical iPhone

## What is missing

ADR-0173's lines were read on the simulator, which redacts nothing, and their
redaction on macOS. On a physical iPhone neither the readable line nor the
refusal after a device moved desktop was seen. A `<private>` there, or no line
at all, is the symptom.

## Why it was accepted

No iPhone was connected to the loop's Mac (`devicectl`: the iPhone 8
`unavailable`), and the loop cannot connect one. The form that is `<private>`
on macOS reads in clear with `.public`, on the same `os_log`.

## Trigger

The next time an iPhone is connected to run the SDK on it (the owner's field
test, or a device end to end). Then: a Debug build launched from the home
screen after moving desktop; Console shows *skipping the desktop at* in clear.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
