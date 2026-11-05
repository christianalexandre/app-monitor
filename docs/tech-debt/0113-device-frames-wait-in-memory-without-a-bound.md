---
id: DEBT-0113
title: Device frames wait in the desktop's memory without a bound while the collector lags
status: Open
date: 2026-10-31
trigger: The desktop logs "Storage queue was full", or runs out of heap with devices connected; or a change to `DeviceConnection.kt`'s frame queue
paid_date:
---

# DEBT-0113 — Device frames wait in the desktop's memory without a bound while the collector lags

## What is missing

`DeviceConnection` reads every frame a device sends into an unbounded
queue (ADR-0167), as Ktor 3.6.0's default channels already did. While the
collector waits, because the storage write queue holds its 64 MiB
(SDD 09), the desktop's heap grows by what the devices keep sending.
Symptom: heap growth or `OutOfMemoryError` with `Storage queue was full N
times` logged at close.

## Why it was accepted

The collector waits only once 64 MiB of envelopes wait for SQLite; 10 000
requests over one session peaked at 2.5 MiB with no wait (`TrafficStoreTest`).
A bound needs a policy: closing the device, or pausing reads while
answering pings apart from them. Neither is needed before the collector is
seen waiting.

## Trigger

The desktop logs "Storage queue was full", or runs out of heap with devices
connected; or a change to `DeviceConnection.kt`'s frame queue.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
