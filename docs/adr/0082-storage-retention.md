---
id: ADR-0082
title: How long the desktop keeps captured traffic, and how it is cleared
status: Proposed
date: 2026-10-24
superseded_by:
---

# ADR-0082 — How long the desktop keeps captured traffic, and how it is cleared

## Context

PLAN-0006 M5.1 (b). With ADR-0081 the desktop writes every exchange, body
and frame to disk; without a bound the file grows for as long as the app is
used. What is deleted, and when, decides what private data stays on disk
(`AGENTS.md`, security / privacy) and what the developer can find later
(product scope).

Already decided, not reopened here:

- Bodies and frame payloads are at most `maxBodyBytes` each, 1 MiB by
  default (ADR-0018 C), stored compressed in their own table (ADR-0005).
- Deleted rows are zeroed if ADR-0081 D.1 is accepted.

Orders of magnitude: a 10k-request session (the M5.4 test) with 2 KiB
bodies is ≈40 MiB before compression; JSON bodies typically compress
several times. One long day of a chatty app can reach hundreds of MiB; a
media upload of 1 MiB per request reaches gigabytes in an hour.

## Options

### A. The bound

1. **Age and size, both**: traffic older than **7 days** is deleted, and
   when the database passes **1 GiB** the oldest traffic goes first until it
   is under 90 % of it. Both editable in the settings (age 1–90 days or
   "never"; size 100 MiB–20 GiB). Covers "what happened yesterday" and
   protects the disk from an upload loop.
2. **Size only** (1 GiB) — keeps a quiet project's traffic for months, a
   chatty one's for hours; the developer cannot predict how far back to
   look.
3. **Run count** (last N runs) — a run can hold minutes or days of traffic;
   no bound on disk.

Recommended: 1.

### B. What goes first

1. **Whole sessions, oldest first by their last envelope**, with their
   exchanges, bodies and frames; a session still connected is never
   deleted. A session is the unit the developer recognizes ("the run on the
   Pixel this morning"), and a half-deleted session shows exchanges whose
   bodies are missing.
2. **Bodies first, then rows** — keeps the list longer and drops the part
   that weighs; a row without its body is mostly useless for debugging.
3. **Individual exchanges, oldest first** — finest grain; leaves sessions
   with holes.

Recommended: 1.

### C. Named runs

1. **A run the developer named is kept past both bounds** until they
   delete it or unpin it; kept runs count toward the size shown in the
   settings, and when kept runs alone pass the size bound the window warns
   instead of deleting them. Naming a run is the developer saying "keep
   this" (ADR-0084).
2. **Named runs obey the same bounds** — simpler; a run saved as a
   baseline for M7 run diffs disappears after 7 days.

Recommended: 1.

### D. When it runs, and clearing

1. **At startup and every 10 minutes while the app runs**, on the storage
   thread, in batches so ingest is not blocked for long; a settings button
   "Clear all data" (with a confirmation) closes the database, deletes the
   files and reopens an empty one, named runs and approvals included. The
   developer can also quit the app and delete the directory ADR-0081 B
   shows.
2. **Only at startup** — a desktop left open for a week grows without
   bound.

Recommended: 1. "Clear all data" keeps approvals if the owner prefers; the
recommendation is to clear them too, so the button means "as if never
installed".

## Decision

Empty while `Proposed`.

## Consequences

- M5.8 tests the bounds with a controlled clock and a controlled size.
- C.1 lets disk use pass the size bound only by the developer's choice, and
  the window says so.
- The 7-day default is a guess; the settings make it cheap to change.
