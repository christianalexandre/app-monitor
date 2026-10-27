---
id: DEBT-0022
title: A stalled collector makes a healthy device miss its ping timeout
status: Open # Open | Paid | Dropped
date: 2026-10-22
trigger: The desktop logs "Storage queue was full" at close, or a session is seen closed with 1011 "Ping timeout" while the device was reachable
paid_date:
---

# DEBT-0022 — A stalled collector makes a healthy device miss its ping timeout

## What is missing

Ktor reads pongs on the same path as data frames. When the desktop's
collector lags, `DeviceSession` stops reading the socket (ADR-0009), pongs
are not read either, and after 15 s the server closes a reachable device
with 1011 "Ping timeout" (ADR-0021).

## Why it was accepted

The M1 ingest is an in-memory snapshot store that never blocks for seconds.
Reading pongs apart from back-pressure needs either a drop policy or a
keep-alive outside Ktor's pinger, a design for when ingest can stall.

## Trigger

The desktop logs "Storage queue was full N times" at close (the collector
waited for the disk at least once), or a session is seen closed with 1011
"Ping timeout" while the device was reachable.

Re-triggered in M5.4: ingest now writes to SQLite (the old trigger) through
a 64 MiB write queue, so the collector only waits once that queue is full
(SDD 09). 10 000 requests over one session peaked at 2.5 MiB with no wait
(`TrafficStoreTest`); `Storage.queueFullWaits` makes the case visible.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
