---
id: DEBT-0018
title: Network events without a request id merge into one exchange
status: Open # Open | Paid | Dropped
date: 2026-10-22
trigger: The M1 SDK starts sending network envelopes to the desktop
paid_date:
---

# DEBT-0018 — Network events without a request id merge into one exchange

## What is missing

`TrafficState` keys exchanges by `(sessionId, requestId)` and accepts an empty
`request_id`, the proto3 default. Two requests whose events omit it collapse
into one `HttpExchange`, and the UI shows one request with fields overwritten
by the other.

## Why it was accepted

Only the fake client sends traffic in M0, and every fixture carries a
request id. Whether such an event is dropped, counted or closes the session is
a protocol rule best written with the first real SDK, alongside DEBT-0008.

## Trigger

The M1 SDK starts sending network envelopes to the desktop.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
