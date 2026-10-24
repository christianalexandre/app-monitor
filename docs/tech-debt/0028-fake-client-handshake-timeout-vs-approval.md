---
id: DEBT-0028
title: The fake client gives up on HelloAck after 10 s, approval included
status: Open # Open | Paid | Dropped
date: 2026-10-22
trigger: A script runs the fake client against the desktop app unattended
paid_date:
---

# DEBT-0028 — The fake client gives up on HelloAck after 10 s, approval included

## What is missing

`tools/fake-client` waits 10 s for `HelloAck` (`HANDSHAKE_TIMEOUT_MS`).
Against the desktop app, a new fake device now waits for the approval
dialog (ADR-0027), so `make fake-traffic` fails with a handshake timeout if
nobody clicks Approve within 10 s. Against `make server` nothing changed:
it approves every device.

## Why it was accepted

The fake client exists to play a device against a running desktop, with
someone at the screen; after one approval the pair stays approved until
the app quits. A flag (`--handshake-timeout` or an approval-aware wait) is
a tool change with no user asking for it yet.

## Trigger

A script that runs the fake client against the desktop app unattended.

Fired in M5.4: the agent's hand run of `make fake-traffic` against
`make desktop` timed out, as nobody could click Approve. Re-triggered on
M5.5: once an approved pair is stored, a pair approved once by hand replays
with no dialog, which may be all the fake client needs; M5.5 decides
between that and a longer wait.

Decided in M5.5: the stored pair is enough. `Fixtures.hello` always sends
the same (`device_id`, `app_id`), so after one approval by hand, within the
10 s, every later `make fake-traffic` is admitted with no dialog, across
restarts, until the pair is forgotten. A longer wait is a tool change no
one needs while a person approves once; the unattended case is the trigger
left.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
