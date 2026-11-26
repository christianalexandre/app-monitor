---
id: DEBT-0138
title: The status line's mDNS problem is read once, at start
status: Open # Open | Paid | Dropped
date: 2026-11-26
trigger: "The owner sees an mDNS problem in the status line while devices find the desktop, or none while they cannot; or a change to `runServer` in `desktop/app/src/main/kotlin/appmonitor/desktop/app/Main.kt`"
paid_date:
---

# DEBT-0138 — The status line's mDNS problem is read once, at start

## What is missing

`runServer` writes `Listening on port N` with `advertisingProblem` once, right after
`start()`. Since ADR-0226 the advertised addresses change while the app runs. A desktop
opened with no network says "no LAN address to advertise on" until it is reopened, even
once Wi-Fi comes back and devices find it. The headless server prints its `mDNS:` line once
too.

## Why it was accepted

What devices see is correct; only the caption can be stale. And it is stale only for a
desktop opened while the network had a problem. Following it means a flow from the server
to the status bar, which ADR-0226's fix did not need.

## Trigger

The owner sees a stale mDNS problem in the status line, or a change to `runServer` in
`Main.kt`.

## How it was paid

> Filled when closing.
