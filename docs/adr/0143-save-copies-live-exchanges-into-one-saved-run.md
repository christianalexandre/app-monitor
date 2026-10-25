---
id: ADR-0143
title: Save copies live exchanges into one "Saved" run, marked by a column
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0143 — Save copies live exchanges into one "Saved" run, marked by a column

## Context

ADR-0140 C: from the live list the user saves selected exchanges ("Save")
into a "Saved" collection listed with the runs; attaching an exchange to
an issue, from the window or MCP, saves it. ADR-0140's consequences ask
for a storage migration that keeps existing runs as recordings.

As built (SDD 09): every stored exchange hangs off a session, every
session off a run (`sessions.run_id NOT NULL`). Readers name stored
traffic by run (`ExchangeFilter.runId`, `list_runs`, the run list), and
retention prunes ended sessions of unnamed runs (ADR-0082). `LiveTraffic`
keeps each exchange's decoded `RequestStarted` and `ResponseReceived`,
its frames, and its failure in the row (ADR-0141); `issue_exchanges`
links stored rows only, so "Save as issue" on live requests came back
`UnknownExchanges` until now.

What has to be decided: where saved exchanges live in the schema, how a
live exchange becomes rows, and how an issue gets them.

## Options

1. **One run marked `saved`.** Migration 7 adds `runs.saved` (0 for every
   existing run, so they stay recordings). The first Save opens a run
   named "Saved", ended at once, with `saved = 1`; each live session gets
   one session row in it (found again by `server_session_id`), and each
   exchange is written by replaying the events `LiveTraffic` kept through
   `Ingest`'s own `network` code, on the writer queue in one item.
   Every reader by run, the run list and `list_runs` work unchanged.
2. **A `saved_exchanges` table beside the runs.** Saved rows would need
   their own session, body and frame tables, or a nullable `run_id`, and
   every reader by run a second path.
3. **A `kind` text column** (`recording` / `saved`). Same as 1 with room
   for more kinds; none is planned.

## Decision

Option 1.

- `Storage.save(LiveTraffic.copies(ids))` returns each live id's stored
  id once written. An exchange already saved (same session and
  `request_id`) is not written again; one gone from the live list is left
  out.
- Retention never prunes the saved run's sessions, even if it is renamed
  blank (`runs.saved = 0` in both pruning queries).
- `desktop/data`'s `TrafficRepository.save` and
  `Storage.issueRepository(live)` route live ids through it; the issue
  then links the stored rows. The window and MCP use that issue
  repository.
- The run list shows "Saved" apart from the recordings; it opens
  read-only like any stored run. `list_runs` gains `saved`.

## Consequences

- One more column and one schema dump (`8.db`); no existing row changes.
- A saved exchange is a copy: events the live list did not keep (other
  channels, which it drops) are not in it.
- Saving many large exchanges is one queue item whose bodies the queue's
  byte bound does not count; the live list's own bound (128 MiB) caps it.
