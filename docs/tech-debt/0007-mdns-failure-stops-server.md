---
id: DEBT-0007
title: mDNS failure on one interface stops the whole server
status: Paid
date: 2026-10-22
trigger: A desktop reports "Server failed" because of one interface, or M1 SDKs connect by explicit host
paid_date: 2026-11-03
---

# DEBT-0007 — mDNS failure on one interface stops the whole server

## What is missing

`MdnsAdvertiser.start` is all or nothing across interfaces, and
`AppMonitorServer.start` treats an advertising failure as fatal. A VPN or
container bridge that passes the LAN filter but rejects JmDNS makes the
desktop show "Server failed" and accept no connection, not even from a device
that knows the host and port. The opposite case is silent: when no address
passes the LAN filter, `start` succeeds with zero registrations and the
desktop shows "Listening" while nothing is published.

## Why it was accepted

M0 only connects the fake client, on a machine whose interfaces all register.
Whether to skip failing interfaces, keep listening without mDNS, or surface a
partial state in the UI is a UX choice better made with real SDKs and real
networks.

## Trigger

A desktop is observed showing "Server failed" because of a single interface,
or the M1 SDKs gain an explicit host setting, which makes listening without
mDNS useful.

## How it was paid

Paid by PLAN-0016 H6.1 (ADR-0174). An address that fails is skipped, the server
listens whatever happens to mDNS, and `advertisingProblem` reaches the window's
status and the headless output, none-at-all included. A failure listing the
interfaces, and an `Error`, still fail `start`.
