---
id: ADR-0197
title: Saving an exchange again stores the frames received since, counted by the writer
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-04
superseded_by:
---

# ADR-0197 — Saving an exchange again stores the frames received since, counted by the writer

## Context

ADR-0143 copies live exchanges into the "Saved" run. Saving one again took its
request and outcome and dropped every `WebSocketFrame`, because writing them all
would store the first save's twice. A live WebSocket saved twice therefore lost
every frame after the first save (finding 2 of PLAN-0016's H6 milestone review).

A frame has no id on the wire (`protocol/proto/appmonitor/v1/`), the live list
cuts its oldest past `maxFramesPerExchange` (`LiveTraffic.kt`), and two frames may
be equal in time and payload. The live list and the store live and die together:
after a restart no live copy of a saved exchange exists to save again.

## Options

1. **The writer counts.** `LiveCopy` carries the frames the exchange received
   ever (`frameCount`); `Ingest` keeps that number per saved exchange in its
   `State` and writes only the kept frames past it. No schema change; `State` is
   already restored when a batch rolls back, and replaced by "Clear all data".
2. **A column on `exchanges`** with the same number. Survives a restart, which
   nothing needs, for a migration and a column empty on every recorded exchange.
3. **Delete the stored frames and write the kept ones.** No state, but frames
   the first save stored and the live list has since cut would be lost.
4. **Compare payloads and times** with the stored tail. Equal frames are legitimate,
   so it would drop real ones.

## Decision

Option 1. An exchange found saved with no count, which only a live copy outliving
its store could be, takes no frames, as before.

## Consequences

- A save writes the frames it can: those the live list cut between two saves are
  not in the saved copy, as ADR-0143 already says of what the list did not keep.
- ADR-0143's "not written again" now means its frames are not written twice.
- One map entry per exchange saved, for the store's lifetime.
