# 09 — Desktop storage

**Built in part** (ADR-0066): the module, its schema, write path and paged
reads are in `desktop/storage` (M5.3), the app writes and reads through
it (M5.4, SDD 01), and approvals are kept in it (M5.5); run names, search
and retention are M5.6 to M5.8 (PLAN-0006).
`desktop/storage` keeps what the desktop receives in one SQLite file through
SQLDelight (ADR-0005), so traffic, runs and approvals survive a restart. Decisions: ADR-0081 (what, where, protection), ADR-0082
(retention), ADR-0083 (approvals), ADR-0084 (runs), ADR-0085 (libraries,
files, connection settings), ADR-0086 (opening, booleans, lost writes).
Code: `desktop/storage/`, used by `desktop/app`.

## How it works

### Files

`<data dir>/appmonitor.db`, with `appmonitor.db-wal` and `-shm` beside it.
The data directory and its resolution order (`APPMONITOR_DATA_DIR`, then
per OS) are ADR-0085's, computed by `dataDirectory()`; the settings show the
path. The WAL and shared-memory files exist while a connection is open: the
store closes its connection after each transaction, so they come and go. On macOS and Linux the
directory is `0700` and the files `0600`.

Opening (`Storage.open(dir)`):

1. Create the directory, set `0700`; create `appmonitor.db` empty with
   `0600` if absent.
2. Open the driver with the connection properties of ADR-0085 (WAL,
   `foreign_keys`, `secure_delete`, `busy_timeout`).
3. `user_version` is read first on one plain connection, without the
   properties: higher than the schema → fail with `NewerDatabaseException`
   (nothing written); 0 → `auto_vacuum = INCREMENTAL` and `VACUUM` on that
   same connection, which applies it whatever the file holds. Then, in one
   transaction on the driver: 0 → create the schema; lower → migrate; the
   version set to the schema's.
4. Recover from a crash: sessions without `disconnected_at_ms` get their
   `last_envelope_at_ms` (or `connected_at_ms`) as end; runs without
   `ended_at_ms` get their last session's end, or their start.
5. Open a new unnamed run (ADR-0084 A.1).

### Tables

Every id is an `INTEGER PRIMARY KEY`; children reference their parent with
`ON DELETE CASCADE`, so deleting a session deletes all it holds.

| Table | Columns | Indexes |
| --- | --- | --- |
| `runs` | `name` (null = unnamed), `started_at_ms`, `ended_at_ms` | `started_at_ms` |
| `devices` | `device_id` (unique), `platform`, `name`, `os_version` (the last `Hello` seen) | |
| `apps` | `app_id` (unique) | |
| `sessions` | `run_id`, `device_ref`, `app_ref`, `server_session_id`, the rest of `Hello` as received (`hello` blob), `app_version`, `sdk_name`, `peer_address`, `connected_at_ms`, `disconnected_at_ms`, `last_envelope_at_ms` | `run_id`; `last_envelope_at_ms` |
| `exchanges` | `session_ref`, `request_id`, `method`, `url`, `host`, `status_code`, `failed`, `started_at_ms`, `ended_at_ms`, `capture_layer`, `request_meta`, `response_meta`, `error`, `frame_count` | unique (`session_ref`, `request_id`); (`session_ref`, `id`); `host`; `status_code`; `method` |
| `bodies` | `exchange_ref`, `part` (request or response), `codec`, `data`, `size` | primary key (`exchange_ref`, `part`) |
| `frames` | `exchange_ref`, `at_ms`, `direction`, `opcode`, `payload_truncated`, `payload_size`, `stored_size` | (`exchange_ref`, `id`) |
| `frame_payloads` | `frame_ref`, `codec`, `data` | primary key `frame_ref` |
| `raw_envelopes` | `session_ref`, `channel`, `type`, `timestamp_ms`, `payload` | (`session_ref`, `id`) |
| `approvals` | `device_id`, `app_id`, `approved`, `decided_at_ms`, `device_name`, `os_version`, `app_version` | primary key (`device_id`, `app_id`) |
| `settings` | `key`, `value` | primary key `key` |

- `request_meta` / `response_meta` are the `RequestStarted` /
  `ResponseReceived` payload re-encoded with Wire **without** its body
  (headers, truncation, sizes and any field this desktop does not know, kept
  by Wire's unknown fields). The columns beside them are what lists, search
  and filters read; `host` is parsed from `url` at ingest.
- `bodies` and `frame_payloads` keep the bytes apart, so list queries never
  read them (ADR-0005). `codec` is `raw` or `deflate` (ADR-0085); `size` is
  the stored byte count before compression, which is what the device sent
  (maybe cut, ADR-0020). An empty body has no row.
- `raw_envelopes` holds what the store does not turn into rows: other
  channels, unknown `network` types, a payload that does not decode
  (ADR-0081 A.1).
- Booleans (`failed`, `payload_truncated`, `approved`) are `INTEGER` 0 or 1.
- `approvals` and `settings` are in the version-1 schema, so M5.5 and M5.8
  need no migration; `settings` has no query yet (M5.8).
- `approvals` and `settings` are not under a session: retention never
  touches them; "Clear all data" does (ADR-0082 D, ADR-0083).

Migrations are `.sqm` files, one per schema version; the version-1 schema is
the `.sq` files. Every change to a table is a new migration; a shipped
migration is never edited. The build verifies them against the committed
`.db` schemas (ADR-0085).

### Write path

```
DeviceSession.envelopes ──► TrafficStore (one coroutine per session)
                               │ enqueue: Connected, Envelope, Disconnected
                               ▼
                         WriteQueue (bounded by bytes, 64 MiB)
                               │
                               ▼
                  writer thread "appmonitor-storage" (one, its own connection)
                    takes what is queued (up to 500 items), one transaction
                    retention passes between batches (ADR-0082 D)
```

- One writer thread owns every write, so writes never contend and
  `SQLITE_BUSY` is not expected on the write side; its dispatcher is a
  single-thread executor, not `Dispatchers.IO`.
- A session's items keep their order: `Connected` is queued before its
  collector starts, `Disconnected` after its flow completes.
- An `Envelope` is decoded on the writer thread, not in the collector, so
  the collector only measures and enqueues.
- One transaction per batch: one commit, one change notification to the
  UI's queries, however many envelopes arrived.
- A `network` event for an exchange not yet stored creates it (as the
  in-memory store does today); a `WebSocketFrame` increments `frame_count`.
- `peer_address` is the device's remote address, which `DeviceSession`
  exposes from M5.3 on.
- Runs take the same queue (ADR-0084): `newRun()` ends the current run and
  opens a new one, returning its id once written; `rename(runId, name)`
  sets the name, blank is null. Each `Connected` keeps the run current when
  the writer reached it, so a session stays in its run whatever comes after.
  `currentRun` (a `StateFlow`) is the writer's current run after each batch.
  A lost batch puts back the run current before it, and a session that
  arrived in it joins that run when it is written again.
- Approvals take the same queue (ADR-0083): `decided(hello, approved)`
  upserts the pair's row with the last `device_name`, OS and app version,
  `forget(deviceId, appId)` and `forgetAll()` delete; queued in click order,
  drained by `close` like envelopes. `approvals()` reads them once at
  launch, `observeApprovals()` follows them for the settings.

### When a write is slow or fails

- **Slow writer.** The queue absorbs it until 64 MiB of encoded envelopes
  are waiting (about 50 frames of `Protocol.MAX_FRAME_BYTES`). Below that
  the collector never waits for SQLite, so `DeviceSession` keeps reading
  the socket and its pongs (DEBT-0022). Full → `enqueue` suspends, which is
  today's back-pressure (ADR-0009): nothing is dropped, and DEBT-0022 is
  back for as long as it lasts. `queueFullWaits` counts those waits and
  `close` logs it when it is not zero. Measured in M5.4: 10 000 requests
  over one real session stored in 0.7 s with a high-water mark of 2.5 MiB
  of 64 MiB and no wait (`TrafficStoreTest` on the owner's MacBook Pro).
- **Failed batch** (any exception from the batch, `SQLException` for disk
  full or I/O error). The transaction rolls back; the batch's items are
  counted as not saved; the writer goes on
  with the next batch, so one bad moment does not stop ingest or close a
  device. `Storage.health` (a `StateFlow`) holds the count and the last
  error; the window shows "N events not saved: <error>" until a later batch
  holding events succeeds. An exchange whose `RequestStarted` was in a lost batch is
  created by its next event, with what that event carries; a session whose
  row was in a lost batch is inserted again from its `Hello`, which the
  writer keeps until the session's `Disconnected`.
- **Failure at open** (directory not writable, `NewerDatabase`, corrupt
  file, stored approvals that cannot be read). The app opens without storage: the window shows the error and the
  path ("Clear all data" there comes with M5.8); the server is not started,
  so devices are not admitted into a desktop that cannot keep them.
- **Close.** On exit, after the server stops, `Storage.close(timeout)`
  queues the run's end and drains the queue with the same `STOP_TIMEOUT` as
  the server; what is still queued after it, the batch being written
  included, is lost, logged and returned as a count. The next open closes
  what it left open.

### Reading

- `Storage`'s reads are suspending functions that move to `Dispatchers.IO`; WAL lets a read see the last committed
  batch while the writer works. Each read is one transaction, so it opens one
  connection, not one per statement (ADR-0085).
- Lists are pages by key, never by `OFFSET`: exchanges of a run or session
  ordered by `id`, `PAGE_SIZE` 200, the next page `WHERE id > :last`. The
  window observes the first N rows (`observeExchangesOfSession`, N grown by
  a page as the list nears its end), the runs newest first (`observeRuns`)
  and the sessions of one run or of all (`observeSessions(runId)`)
  through SQLDelight's `Query` listeners (`coroutines-extensions`), which
  re-run them after each commit, conflated so a burst of batches is one
  refresh.
- The detail of one exchange reads its meta, its bodies (inflated on read)
  and its frames by page.
- Search and filters (M5.7) are `WHERE` clauses on the indexed columns.

### Tests that drive it

Each case above, with the test that proves it (M5.3 unless noted):

| Case | Test |
| --- | --- |
| Directory and file created `0700` / `0600`, WAL inherits it | `StorageOpenTest.createsOwnerOnlyFiles` (POSIX only) |
| Schema created at 0, `auto_vacuum` incremental | `StorageOpenTest.createsSchemaOnEmptyFile` |
| Migrations verified against committed schemas | the plugin's migration verification task |
| Newer `user_version` refused, file untouched | `StorageOpenTest.refusesNewerDatabase` |
| Sessions and runs left open by a crash are closed | `StorageOpenTest.closesWhatACrashLeftOpen` |
| Each launch opens a new unnamed run | `StorageOpenTest.opensANewRun` |
| `Hello` and the four `network` events round-trip, bodies byte for byte, unknown fields kept | `IngestTest.roundTripsEveryEvent` |
| An event for an unknown request creates it | `IngestTest.eventBeforeStartCreatesExchange` |
| Other channels, unknown types, undecodable payloads land in `raw_envelopes` | `IngestTest.keepsWhatItDoesNotDecode` |
| Compressible bodies deflated, incompressible ones raw | `IngestTest.picksCodecPerBody` |
| One commit per batch | `WriteQueueTest.oneTransactionPerBatch` |
| A slow writer does not stall the collector below the bound | `WriteQueueTest.slowWriterDoesNotBlockEnqueue` |
| A full queue suspends `enqueue`, nothing dropped | `WriteQueueTest.fullQueueSuspends` |
| A failed batch is counted, the next one is written | `WriteQueueTest.failedBatchIsCountedAndSkipped` |
| Close drains the queue within the timeout | `WriteQueueTest.closeDrainsQueue` |
| Close past the timeout returns what it lost | `WriteQueueTest.closeGivesUpAfterTimeout` |
| Data directory per OS and its override | `DataDirectoryTest` |
| Pages by key, stable while rows are added | `PagingTest.pagesByKeyWhileIngesting` |
| Observed pages and sessions follow commits | `PagingTest.observedPagesFollowCommits` |
| A 10k-request session ingested in bounded time, restart shows it one click away in the run picker (M5.6), the list reads its next page | `TrafficStoreTest` in `desktop/app` (M5.4) |
| Approvals stored, reopened, forgotten | `ApprovalStoreTest` (M5.5), and in `desktop/app` across three launches with a real server |
| Sessions grouped by run, runs named | `RunsTest` (M5.6): a session spanning "New run" stays in its run, names kept across a restart, a lost "New run" keeps the earlier run |
| One case per filter and their combination | `SearchTest` (M5.7) |
| Age and size bounds, oldest session first, connected and named kept, secure delete | `RetentionTest` (M5.8) |

## Limits

- Not encrypted: owner-only permissions are the protection (ADR-0081 C.1).
- One desktop per data directory: a second instance on the same directory
  is not detected (DEBT-0062); `APPMONITOR_DATA_DIR` separates them.
- A batch lost to a write failure is not retried.
