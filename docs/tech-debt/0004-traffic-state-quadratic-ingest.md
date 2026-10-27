---
id: DEBT-0004
title: TrafficState ingest is linear per event
status: Paid
date: 2026-10-21
trigger: M5 replaces the in-memory store with SQLDelight, or a session over 10k requests is measured to lag the UI
paid_date: 2026-10-24
---

# DEBT-0004 — TrafficState ingest is linear per event

## What is missing

`TrafficState` keeps exchanges in one list: every network event runs
`indexOfFirst` over all exchanges and copies the list, and `exchangesOf`
re-filters it on each recomposition, and each row compares itself with the
selected exchange by data-class equality, deep-comparing headers and bodies. A WebSocket frame also copies its exchange's frame list (ADR-0029). Ingest is O(n) per event, so a long
session is quadratic overall and the UI slows as traffic accumulates.

The payload is decoded inside the `MutableStateFlow.update` lambda, so
concurrent sessions that lose a compare-and-set decode it again.

## Why it was accepted

Found in the M0 milestone review. The store is a skeleton (PLAN-0001 M0.5);
M5 replaces it with SQLDelight and paged queries, so an index keyed by
`(sessionId, requestId)` now would be rewritten. Fixture traffic is a handful
of requests.

## Trigger

M5 `desktop/storage` replaces the in-memory store, or a session over 10k
requests is measured to lag the UI — whichever comes first.

## How it was paid

Paid in M5.4 (`feat/desktop-app-on-storage`): `TrafficState` is gone. The
app writes through `desktop/storage`, where an event is one indexed upsert
by `(session_ref, request_id)` and a frame one insert, and the window
observes one page of rows instead of the whole history. `TrafficStoreTest`
stores 10 000 requests over a real session in 0.7 s. Decoding moved to
the storage writer thread, so no compare-and-set retries it.
