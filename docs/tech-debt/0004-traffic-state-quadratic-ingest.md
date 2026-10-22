---
id: DEBT-0004
title: TrafficState ingest is linear per event
status: Open
date: 2026-10-21
trigger: M5 replaces the in-memory store with SQLDelight, or a session over 10k requests is measured to lag the UI
paid_date:
---

# DEBT-0004 — TrafficState ingest is linear per event

## What is missing

`TrafficState` keeps exchanges in one list: every network event runs
`indexOfFirst` over all exchanges and copies the list, and `exchangesOf`
re-filters it on each recomposition, and each row compares itself with the
selected exchange by data-class equality, deep-comparing headers and bodies. Ingest is O(n) per event, so a long
session is quadratic overall and the UI slows as traffic accumulates.

## Why it was accepted

Found in the M0 milestone review. The store is a skeleton (PLAN-0001 M0.5);
M5 replaces it with SQLDelight and paged queries, so an index keyed by
`(sessionId, requestId)` now would be rewritten. Fixture traffic is a handful
of requests.

## Trigger

M5 `desktop/storage` replaces the in-memory store, or a session over 10k
requests is measured to lag the UI — whichever comes first.

## How it was paid

> Filled when closing.
