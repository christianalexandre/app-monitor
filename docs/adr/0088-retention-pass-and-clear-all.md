---
id: ADR-0088
title: Retention measures live pages, deletes in chunks on the writer thread, and "Clear all data" empties the file in place
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0088 — Retention measures live pages, deletes in chunks on the writer thread, and "Clear all data" empties the file in place

## Context

PLAN-0006 M5.8 builds ADR-0082: 7 days and 1 GiB, both editable; whole
sessions go, oldest last envelope first; connected sessions and named runs
stay; a pass at startup and every 10 minutes; "Clear all data" leaves the
store "as if never installed". Not reopened here: what is deleted and kept
(ADR-0082), `auto_vacuum = INCREMENTAL` and `secure_delete = ON` (ADR-0085,
ADR-0086), one writer thread (SDD 09).

What ADR-0082 leaves to the build:

- **What "the database size" is.** With incremental auto-vacuum, deleted
  pages go to the free list and the file keeps its size until
  `PRAGMA incremental_vacuum` hands them back.
- **How free pages are handed back.** SQLite frees one page per step of
  that pragma (one empty result row each). The sqlite-jdbc driver pinned by
  ADR-0085 steps a statement once: measured in `RetentionTest` on
  2026-10-24, one `execute` shrank a file with 100 free pages by 4096 bytes,
  and `executeQuery` refuses it ("Query does not return results").
- **How "Clear all data" empties the store.** ADR-0082 D.1 describes it as
  closing the database, deleting the files and reopening. Live sessions,
  the UI's queries and the writer all hold the `Storage`; replacing it
  means rebuilding the app's object graph while devices are connected.

## Options

### A. Size measured

1. **Live pages**, `(page_count − freelist_count) × page_size`, on a
   connection of the store; the settings show the file's size. What retention
   deletes counts at once, whether the file has shrunk yet or not.
2. **The file's size** on disk — only falls after the free pages are handed
   back, and includes the WAL, which comes and goes (SDD 09).

### B. Handing pages back

1. **`PRAGMA incremental_vacuum` repeated**, one page per statement, up to
   1 000 per transaction, until the free list is empty or stops shrinking;
   the writer yields between transactions.
2. **`VACUUM` after each pass** — rewrites the whole file, blocking ingest
   for seconds on a large one.
3. **Never** — free pages are reused by later inserts, so the file stays at
   its high-water mark; lowering the limit would not give the disk back.

### C. Clear all data

1. **Delete every row in one transaction, then `VACUUM`**, on the writer
   thread: `secure_delete` zeroes the rows, `VACUUM` rewrites the file to an
   empty schema, a new run opens. The `Storage` stays, so nothing above it
   changes; the writer forgets its live sessions, whose later events are
   dropped. The app forgets every approval first, which closes approved
   devices (ADR-0083).
2. **Close, delete the files, reopen** — what ADR-0082 D.1 describes; the
   result on disk is the same, at the cost of swapping the store under the
   server and the window.

In the window shown when the store did not open (SDD 09) there is no store:
there the button deletes `appmonitor.db`, `-wal` and `-shm`, and the
developer reopens the app.

## Decision

A.1, B.1, C.1. The pass runs on the writer thread between batches: age
first (50 sessions per transaction), then size (one session per statement,
measured after each) until live pages are under 90 % of the limit. It also
deletes unnamed, ended runs left without sessions (older than the age limit,
or any of them after a size pass) and devices and apps no session refers
to. `Storage` schedules it at open and every 10 minutes; `setRetention`
writes the settings through the queue and applies them at once.

## Consequences

- The file shrinks after each pass that deleted something, by repeated
  statements: the pass is longer on a store with many free pages, and its
  chunks let ingest go on between them.
- The device list warns when what is kept still passes the limit
  (ADR-0082 C.1) or when a pass failed.
- "Clear all data" blocks ingest for the length of one `VACUUM` of an empty
  schema after a delete of everything; on a 1 GiB file the delete
  dominates, as with any `secure_delete`.
- `Storage.runId` stays the launch's run even after a clear deleted it;
  `currentRun` is the run to use.
