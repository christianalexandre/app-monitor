---
id: ADR-0086
title: How the store prepares a new file, stores booleans and counts lost writes
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0086 — How the store prepares a new file, stores booleans and counts lost writes

## Context

PLAN-0006 M5.3 builds `desktop/storage` on ADR-0085 and SDD 09. Building it
raised choices those leave open. Evidence, gathered on 2026-10-24:

- **`auto_vacuum` on a file that already has a header.** `sqlite3` 3.x on
  macOS: a file set to `journal_mode=WAL` with no table, then
  `PRAGMA auto_vacuum = INCREMENTAL` and a `CREATE TABLE` on later
  connections, reads back `auto_vacuum` 0; the same with `VACUUM` right
  after the pragma, on its connection, reads back 2. On a 0-byte file both
  read 2. A crash between the first connection and the schema leaves such a
  header with `user_version` 0.
- **Booleans.** `failed INTEGER AS Boolean` in a `.sq` file makes SQLDelight
  2.4.1 generate an `Exchanges.Adapter` with a required
  `ColumnAdapter<Boolean, Long>` and an unresolved `import Boolean` in the
  generated code (`build/generated/sqldelight`).
- **Lost-write count.** A coroutine suspended on `Channel.receive` takes the
  item a later `trySend` hands it before it runs again, so counting what is
  left in the channel after cancelling the writer misses that item.
- **Peer address.** Ktor's `RequestConnectionPoint.remoteAddress` on
  `call.request.local` is the socket's peer, before any forwarded header
  (`AppMonitorServerTest` reads `127.0.0.1`).

## Options

1. **What ADR-0085 says, literally** — the pragma alone, `AS Boolean` with
   adapters, the channel's leftovers as the lost count.
2. **The pragma then `VACUUM` on one plain connection, booleans as plain
   `INTEGER` 0/1 mapped in Kotlin, a counter of queued items** — each fixes
   one item above at the cost of a few lines.

## Decision

Option 2:

- `Storage.open` reads `user_version` on one plain JDBC connection without
  the connection properties; higher than the schema → `NewerDatabaseException`
  before the driver opens; 0 → `PRAGMA auto_vacuum = INCREMENTAL` then
  `VACUUM` on that connection.
- Boolean columns are `INTEGER` holding 0 or 1; `Storage`'s records expose
  them as `Boolean`.
- `WriteQueue` counts queued items (not test barriers) in an `AtomicLong`;
  `close` returns that count when it gives up, the batch in flight
  included.
- Any exception from a batch, not only `SQLException`, is counted and
  skipped, so a bug in one event's handling cannot stop the writer and,
  with it, every `enqueue` once the queue fills.
- `approvals` and `settings` are in the version-1 schema though no query
  reads them before M5.5 and M5.8, so those tasks add no migration.
- `DeviceSession.peerAddress` is `call.request.local.remoteAddress`.

## Consequences

- An empty file costs one `VACUUM` at first open, on an empty database.
- The lost count can include a batch that commits after `close` returned;
  it errs towards reporting a loss.
- A writer bug shows as "N events not saved" instead of a stalled desktop;
  its stack is not kept, only `toString()`.
