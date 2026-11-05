---
id: DEBT-0015
title: The 5 s stop bound may cut a slow mDNS close
status: Paid
date: 2026-10-22
trigger: A "Server stop timed out" status or a shutdown hook timeout seen on a real desktop
paid_date: 2026-11-03
---

# DEBT-0015 — The 5 s stop bound may cut a slow mDNS close

## What is missing

`STOP_TIMEOUT` (5 s) bounds `AppMonitorServer.stop`, which closes one JmDNS
instance per LAN address in sequence and then stops the engine (up to 1 s).
Each `JmDNS.close` sends goodbye records and waits, so a host with several
multicast interfaces may pass the bound. Symptom: the app shows "Server stop
timed out" on close, or the process exits with the mDNS records still
published until their TTL expires.

## Why it was accepted

No stop over the bound has been seen: the tests and the developer machine
advertise on one or two addresses. Measuring JmDNS close times per interface
and closing in parallel is work for a case that has not shown up.

## Trigger

A "Server stop timed out" status or a shutdown hook timeout seen on a real
desktop.

## How it was paid

Paid by PLAN-0016 H6.1 (ADR-0174). Measuring found the trigger already met on the
development machine: 3014 ms and 2016 ms closing two addresses in turn, the whole
`STOP_TIMEOUT`. They close at once now, within 3.5 s, and `make server` stopped in 3.06 s.
