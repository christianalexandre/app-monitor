---
id: DEBT-0107
title: A proxy event dropped past its bound leaves half an exchange
status: Open
date: 2026-10-30
trigger: "The desktop logs `Proxy recording dropped`, or the next PR that changes how `desktop/proxy/src/main/kotlin/appmonitor/desktop/proxy/ProxyRecording.kt` bounds or drops events"
paid_date:
---

# DEBT-0107 — A proxy event dropped past its bound leaves half an exchange

## What is missing

`ProxyRecording` drops a new event once 64 MiB wait for the store (#312,
SDD 11), whatever its type. A dropped `RequestStarted` lets its
`ResponseReceived` create an exchange with no method, URL or host, as
`Ingest` does for an unknown request. A dropped `ResponseReceived` or
`RequestFailed` leaves its exchange pending for good. Nothing on the
exchange says an event of it was dropped.

## Why it was accepted

The bound needs 64 MiB of proxy events waiting for the store, which
means a stalled disk. Before #312 the same case grew memory without
limit. Stopping the proxy logs the count of dropped events, so the case
is visible when it happens.

## Trigger

The desktop logs `Proxy recording dropped`, or the next PR that changes
how
`desktop/proxy/src/main/kotlin/appmonitor/desktop/proxy/ProxyRecording.kt`
bounds or drops events. Then drop whole exchanges instead of single
events (an exchange's later events go once its first one was dropped),
or mark the exchange as cut.

## How it was paid

> Filled when closing.
