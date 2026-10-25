# 09 — Desktop storage

`desktop/storage` keeps what the desktop receives in one SQLite file through
SQLDelight (ADR-0005), so traffic, runs and approvals survive a restart. Decisions: ADR-0081 (what, where, protection), ADR-0082
(retention), ADR-0083 (approvals), ADR-0084 (runs), ADR-0085 (libraries,
files, connection settings), ADR-0086 (opening, booleans, lost writes),
ADR-0087 (search queries), ADR-0088 (retention pass, clearing), ADR-0095
(issues), ADR-0096 (issue rows, their writers, retention of their exchanges),
ADR-0140 (live traffic in memory, only recordings stored), ADR-0141 (the live
list's types, ids and bound), ADR-0142 (recording through `Recorder`),
ADR-0143 (Save into one "Saved" run).
Code: `desktop/storage/`, used by `desktop/app`.

## How it works

### Files

`<data dir>/appmonitor.db`, with `appmonitor.db-wal` and `-shm` beside it.
The data directory and its resolution order (`APPMONITOR_DATA_DIR`, then
per OS) are ADR-0085's, computed by `dataDirectory()`; the settings show the
path. The WAL and shared-memory files exist while a connection is open: the
store closes its connection after each transaction, so they come and go. On macOS and Linux the
directory is `0700` and the files `0600`. The proxy's CA, `proxy-ca.pem` and
`proxy-ca-key.pem`, sits beside them, `0600` too, written by `desktop/proxy`
on the proxy's first start; "Clear all data" deletes it (SDD 11).

Opening (`Storage.open(dir)`):

1. Create the directory with `0700` if absent; an existing one, such as an
   `APPMONITOR_DATA_DIR` the developer chose, keeps its mode. Create
   `appmonitor.db` empty with `0600` if absent.
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

Every id is an `INTEGER PRIMARY KEY` (`mock_rules` and `breakpoint_rules`
add `AUTOINCREMENT`, so a rule's id is never reused, ADR-0113 B.1); children reference their parent with
`ON DELETE CASCADE`, so deleting a session deletes all it holds.

| Table | Columns | Indexes |
| --- | --- | --- |
| `runs` | `name` (null = unnamed), `started_at_ms`, `ended_at_ms`, `saved` (1 for the "Saved" collection, 0 for a recording) | `started_at_ms` |
| `devices` | `device_id` (unique), `platform`, `name`, `os_version` (the last `Hello` seen) | |
| `apps` | `app_id` (unique) | |
| `sessions` | `run_id`, `device_ref`, `app_ref`, `server_session_id`, the rest of `Hello` as received (`hello` blob), `app_version`, `sdk_name`, `peer_address`, `connected_at_ms`, `disconnected_at_ms`, `last_envelope_at_ms` | `run_id`; `last_envelope_at_ms` |
| `exchanges` | `session_ref`, `request_id`, `method`, `url`, `host`, `status_code`, `failed`, `started_at_ms`, `ended_at_ms`, `capture_layer`, `request_meta`, `response_meta`, `error`, `frame_count`, `grpc_status`, `mock_rule_id`, `replay_of` | unique (`session_ref`, `request_id`); (`session_ref`, `id`); `host`; `status_code`; `method` |
| `bodies` | `exchange_ref`, `part` (request or response), `codec`, `data`, `size` | primary key (`exchange_ref`, `part`) |
| `frames` | `exchange_ref`, `at_ms`, `direction`, `opcode`, `payload_truncated`, `payload_size`, `stored_size` | (`exchange_ref`, `id`) |
| `frame_payloads` | `frame_ref`, `codec`, `data` | primary key `frame_ref` |
| `raw_envelopes` | `session_ref`, `channel`, `type`, `timestamp_ms`, `payload` | (`session_ref`, `id`) |
| `approvals` | `device_id`, `app_id`, `approved`, `decided_at_ms`, `device_name`, `os_version`, `app_version` | primary key (`device_id`, `app_id`) |
| `settings` | `key`, `value` | primary key `key` |
| `issues` | `title`, `note`, `status` (`open` or `resolved`), `source` (`window` or `mcp`, who created it), `created_at_ms`, `updated_at_ms` | |
| `issue_tags` | `issue_ref`, `tag` | primary key (`issue_ref`, `tag`); `tag` |
| `issue_exchanges` | `issue_ref`, `exchange_ref` | primary key (`issue_ref`, `exchange_ref`); `exchange_ref` |
| `mock_rules` | `app_id`, `position`, `name`, `enabled`, `pending`, `source` (`window` or `mcp`), `rule` (the `MockRule` without its id), `created_at_ms`, `updated_at_ms` | (`app_id`, `position`) |
| `breakpoint_rules` | `app_id`, `position`, `name`, `enabled`, `rule` (the `BreakpointRule` without its id), `created_at_ms`, `updated_at_ms` | (`app_id`, `position`) |

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
  needed no migration. `settings` holds `retention.max_age_days` (days, or
  `never`) and `retention.max_bytes`; a missing or unreadable value is the
  default. `mcp.token` is the MCP server's bearer token and `mcp.enabled`
  its switch (SDD 10); `ui.theme` is the window's theme (`system`, `light`
  or `dark`, missing or unreadable = `system`, ADR-0145), read at launch and
  written back after "Clear all data" so clearing keeps it; all read with
  `setting(key)` and written through the queue with `setSetting(key, value)`.
- `approvals` and `settings` are not under a session: retention never
  touches them; "Clear all data" does (ADR-0082 D, ADR-0083).
- Issues (ADR-0095, ADR-0096) are not under a session either; their tags and
  links cascade from the issue, and a link from its exchange. Tags are
  trimmed, blank ones dropped, line breaks turned to spaces, each once.
- Mock and breakpoint rules (SDD 12) are not under a session: retention
  never touches them; "Clear all data" does. A call a breakpoint holds is
  not stored, only its `BreakpointHit` in `raw_envelopes` (ADR-0118 D.1).

Migrations are `.sqm` files in `src/main/sqldelight/migrations/`, one per
schema version: `1.sqm` (version 1 to 2) adds the issue tables, `2.sqm`
(version 2 to 3) `exchanges.grpc_status`, `3.sqm` (version 3 to 4)
`mock_rules`, `4.sqm` (version 4 to 5) `exchanges.mock_rule_id`, `5.sqm`
(version 5 to 6) `breakpoint_rules`, `6.sqm` (version 6 to 7)
`exchanges.replay_of`. The `.sq`
files are the current schema. Every change to a table is a new migration; a shipped
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
- A `ResponseReceived` with status 0, what a closed `CONNECTION` sends
  (ADR-0103), stores `status_code` null: no status (ADR-0104).
- A `ResponseReceived` stores its last `grpc-status` header as a number in
  `grpc_status`, null without one; rows written before version 3 keep null
  (ADR-0107). `StoredExchange.isError` is failed, status 400 and up, or
  `grpc_status` other than 0: what endpoint tallies count as errors.
- A `RequestStarted` stores its `mock_rule_id` (ADR-0110 E.1), null when
  the network answered; rows written before version 5 keep null. The id is
  kept as the device sent it, and outlives its rule: `exchange(id)` gives
  `ExchangeDetail.mockRuleName` from `mock_rules`, null once the rule is
  deleted (ADR-0116).
- A `RequestStarted` stores its `replay_of`, the request id of the request
  it sent again (ADR-0111 D.1), null for a call the app made; rows written
  before version 7 keep null. `exchange(id)` gives
  `ExchangeDetail.replayOfId`, the row of that request id in the same
  session, null once it is gone (ADR-0120 B.1); `replayOriginals(rows)`
  resolves the same for a page of rows in one read, and `session(id)`
  reads one session (`replay_request`, ADR-0121).
- `peer_address` is the device's remote address, which `DeviceSession`
  exposes from M5.3 on.
- Runs take the same queue (ADR-0084): `newRun()` ends the current run and
  opens a new one, returning its id once written; `rename(runId, name)`
  sets the name, blank is null. Each `Connected` keeps the run current when
  the writer reached it, so a session stays in its run whatever comes after.
  `currentRun` (a `StateFlow`) is the writer's current run after each batch.
  A lost batch puts back the run current before it, and a session that
  arrived in it joins that run when it is written again.
- **Recording** (ADR-0140 B, ADR-0142): `record()` is a `newRun()` that
  also sets `recording` (a `StateFlow`, null when none) to the new run,
  returning its id once written, null when lost; `stop()` ends it and
  waits for the write. `clearAll()` and `close` end it too; an open that
  finds a run without an end (a crash while recording) closes it. The
  window opens the store with `Storage.open(dir, startRun = false)`: no
  run at launch nor after `clearAll`, `runId` is 0, and a session is only
  written once `Recorder` gives it a run. Tests keep the default, a run
  per launch.
- **Save** (ADR-0140 C, ADR-0143): `save(copies)` takes
  `LiveTraffic.copies(ids)` (each live exchange's session and the events
  the live list kept) and writes them in one queue item into the run with
  `saved = 1`, which the first save opens, named "Saved" and ended at
  once. Each live session gets one session row there, found again by
  `server_session_id`; each exchange is written by `Ingest`'s own event
  code. One already saved is not written again. Returns each live id's
  stored id once written, nothing when lost or closed.
- Approvals take the same queue (ADR-0083): `decided(hello, approved)`
  upserts the pair's row with the last `device_name`, OS and app version,
  `forget(deviceId, appId)` and `forgetAll()` delete; queued in click order,
  drained by `close` like envelopes. `approvals()` reads them once at
  launch, `observeApprovals()` follows them for the settings.
- Issues take the same queue (ADR-0096). Their writers, in the order the
  writer thread applies them:

  | Writer | Writes | How |
  | --- | --- | --- |
  | Window ("Save as issue", the issue editor) | create, edit, delete | `createIssue`, `updateIssue`, `deleteIssue` |
  | MCP `save_issue` (SDD 10) | create, edit | `createIssue`, `updateIssue` |
  | Retention pass | deletes an issue's session's other exchanges, never a linked one | between batches |
  | "Clear all data" | deletes every issue, tag and link | between batches, after a flush |

  An edit is an `IssueChange`: each null field keeps what is stored, so
  two writers editing different fields both land, and on the same field
  the one queued last wins. `tags` and `exchangeIds` replace the stored
  set. The exchange ids are checked on the writer, in the batch's
  transaction: one that is not stored by then writes nothing and returns
  `UnknownExchanges` with those ids, an edit of a deleted issue
  `UnknownIssue`. Both calls return an `IssueWrite` once their batch is
  committed; a lost batch or a closing store is `NotSaved`.

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
  writer keeps until the session's `Disconnected` is committed. A
  `Disconnected` in a lost batch is retried before each next batch, each
  in its own transaction, so the session does not stay connected for the
  rest of the launch and a disconnect that keeps failing never takes the
  next batch or another lost disconnect with it; once written it leaves
  the count and clears the error.
- **Failure at open** (directory not writable, `NewerDatabase`, corrupt
  file, stored approvals that cannot be read). The app opens without storage: the window shows the error, the
  path and "Clear all data", which deletes `appmonitor.db`, `-wal` and
  `-shm` after a confirmation and asks the developer to reopen the app
  (ADR-0088); the server is not started, so devices are not admitted into
  a desktop that cannot keep them.
- **Close.** On exit, after the server stops, `Storage.close(timeout)`
  queues the run's end and drains the queue with the same `STOP_TIMEOUT` as
  the server; what is still queued after it, the batch being written
  included, is lost, logged and returned as a count. The next open closes
  what it left open. The writer stops at the next batch boundary and
  completes what it will not write, so a `flush` or `newRun` waiting on it
  returns; the database is closed on the writer thread, behind what is
  queued there, once the writer has left its batch, which after a timeout
  may be after `close` returned. A `flush` or `newRun` after `close`
  returns at once. Once `close` starts, `clearAll`, `applyRetention` and
  `setRetention` do nothing and return, a call already queued on the writer
  thread included, so none touches a closed database. Reads and observed
  queries stop too: a read returns nothing (an empty list, a null
  exchange), an observed flow ends at the next commit or before, and the
  database closes only once every read in flight has returned.

### Retention

ADR-0082 decides what goes, ADR-0088 how. `RetentionSettings` (`maxAgeDays`,
null for "Forever"; `maxBytes`) defaults to 7 days and 1 GiB; the settings
offer 1–90 days and 100 MiB–20 GiB. `setRetention` writes them through the
queue, so they follow the batch order and roll back with a lost batch, then
applies them.

`applyRetention()` runs on the writer thread, between batches, at open and
every `RETENTION_INTERVAL` (10 minutes) from a coroutine `close` cancels:

1. **Age.** Ended sessions (`disconnected_at_ms` set) of unnamed runs, never
   the saved run's, whose last envelope (or connection) is older than the
   limit, oldest first, 50
   per transaction; the cascades delete their exchanges, bodies, frames and
   raw envelopes. A session holding an exchange an issue links is kept
   (ADR-0095 B.1, ADR-0096 C.1): its other exchanges and its raw envelopes
   are deleted, the linked ones with their bodies and frames stay, and the
   session, its device and app rows with them; once nothing else is left
   it is no longer a candidate. Then unnamed, ended runs left without
   sessions and older than the limit.
2. **Size.** While live pages (`page_count − freelist_count`, times
   `page_size`) pass the limit, the oldest such session goes, one per
   statement, until they are under 90 % of it; then every unnamed, ended run
   without sessions.
3. Devices and apps no session refers to are deleted, and the free pages
   handed back with `PRAGMA incremental_vacuum`, one page per statement (the
   driver steps a statement once), 1 000 per transaction. Every pass runs
   this step, so one that deleted nothing still cleans up after a superseded
   one.

The writer yields between transactions, so batches pass during a long pass.
A pass whose settings changed (`setRetention`, which runs its own pass with
the new ones) or that a `clearAll()` ran under stops at the next chunk and
leaves `retentionStatus` to the newer one.
`retentionStatus` holds the file's size, how many sessions the last pass
deleted (a trimmed one is not counted), `keptOverBound` when what is kept
(named runs, connected sessions, issues' exchanges)
still passes the limit, and the last error; a failed pass is reported
there, not thrown. The device list shows a warning for either of the last
two, the settings the size on disk.

`clearAll()` (the settings' "Clear all data", after a confirmation) waits
for the queue, then on the writer thread deletes every row — runs, issues, mock
rules, approvals and settings included — in one transaction, runs `VACUUM`, and
opens a new run. The writer forgets the sessions still connected, so what
they send next is dropped; the app first forgets every approval, which
closes the approved devices (ADR-0083). A failure goes to
`retentionStatus`.

### Live list

`LiveTraffic` holds the live list in memory (ADR-0140 A, ADR-0141): every
device's sessions and exchanges since the last `clear()`, as the store's
row types. The device server (`TrafficStore`) and the proxy
(`ProxyRecording`) feed `Recorder`, which feeds it and, while recording,
`Storage` (below): no exchange reaches the store unless it starts while
recording. The window, its exports and benchmarks, and the MCP server read
through the routed repositories below.

- **Fed** by `TrafficSink`'s `connected`, `received`, `disconnected`, which
  `Storage` implements too. The four `network` events are decoded as
  `Ingest` does (status 0 is no status, the last `grpc-status`, an event
  for an unknown request creates it); other channels and payloads that do
  not decode are dropped.
- **Ids** come from one counter starting at `LiveTraffic.FIRST_ID` (2^50)
  for sessions, exchanges and frames; `LiveTraffic.isLive(id)` tells a
  live id from a row id. A live session's `runId` is 0.
- **Bound**: 10 000 exchanges and 128 MiB of bodies and frame payloads
  (`MAX_EXCHANGES`, `MAX_BYTES`); past either the oldest exchange goes,
  and an ended session with it once it holds none. An ended session that
  sent nothing is dropped at its end.
- **Clear** empties the exchanges and the ended sessions; connected
  sessions stay, and their next exchange gets a new id.
- **Reads** mirror the store's: `searchExchanges`, `searchExchangeSizes`,
  `observeExchanges` and `observeSessions` (again after each change, on
  `Dispatchers.Default`), `exchange(id)` with `replayOfId`, `frames`,
  `session`, `replayOriginals`, `groupByEndpoint`. The filter match
  follows `SearchQuery`'s; a filter with a run matches nothing. The mock
  rule's name is left null: `desktop/data` reads it from `mock_rules`.
- **Which one a read means** (`desktop/data`'s `Storage.trafficRepository(live)`,
  `trafficQueryRepository(live)`, `benchmarkRepository(live)`): a filter
  with a session goes where that session lives, else one with a run to
  the store, else the live list; a read by id by `isLive`; runs, run
  diffs, health and issues are the store's. `TrafficRepository.save` and
  `Storage.issueRepository(live)` save the live ids they are given first
  (ADR-0143), so an issue made from live requests links their saved rows;
  one gone from the live list comes back `UnknownExchanges`.

### Recorder

`Recorder` is the `TrafficSink` the window wires (ADR-0142). Every call
goes to `LiveTraffic` first; then:

- A `RequestStarted` read while `Storage.recording` is set ties its
  request id to that run, in the session; every later event of that id
  (response, failure, frames) goes to the store in that run, after
  `stop()` too, until the session ends. Events of a request that started
  outside a recording are not stored. Only field 1 (`request_id`) of the payload is read; bodies are
  skipped.
- Envelopes of other channels, and `network` types this desktop does not
  know, go to the store while recording, in the recording's run (as
  `raw_envelopes`).
- A session gets a row in a run with its first stored envelope there:
  its own `Hello`, peer and connection time, and `server_session_id`
  as the device sent it. A session spanning two recordings has a row in
  each, ended together when the device disconnects.
- `forget()`, after `clearAll()`, drops which requests were recorded, so
  nothing more of them is written.

### Reading

- `Storage`'s reads are suspending functions that move to `Dispatchers.IO`; WAL lets a read see the last committed
  batch while the writer works. Each read is one transaction, so it opens one
  connection, not one per statement (ADR-0085).
- Lists are pages by key, never by `OFFSET`: exchanges of a run or session
  ordered by `id`, `PAGE_SIZE` 200, the next page `WHERE id > :last`. The
  window observes the first N rows (`observeExchanges`, N grown by
  a page as the list nears its end), the runs newest first (`observeRuns`)
  and the sessions of one run or of all (`observeSessions(runId)`)
  through SQLDelight's `Query` listeners (`coroutines-extensions`), which
  re-run them after each commit, conflated so a burst of batches is one
  refresh.
- The detail of one exchange reads its meta, its bodies (inflated on read)
  and its frames by page.
- `runSummaries(beforeId, limit)` reads a page of runs newest first, each
  with its sessions (device and app from `devices` and `apps`) and its
  exchange count, in one transaction; `list_runs` answers from it (SDD 10).
- `isClosing` is true once `close` started: reads then answer empty or
  null, so a caller that must not mistake that for "nothing stored" checks
  it after its reads.
- Search and filters (ADR-0087): `searchExchanges(filter, afterId)` and
  `observeExchanges(filter, limit)` take an `ExchangeFilter` — text in
  `url` (the host is in it; `LIKE`, ASCII case-insensitive, `%` and `_`
  literal), method, `StatusClass`es (1xx to 5xx, any one of them), hosts
  (any one of them, ADR-0148), failed only, device id, app id, run,
  session; unset or empty fields match all, set ones combine with AND.
  `SearchQuery` writes only the clauses of the fields set, so SQLite plans
  each combination with its index: `exchanges_method`,
  `exchanges_status_code`, `exchanges_host`, and `exchanges_session_ref_id` under a subquery
  on `sessions` for session, run, device and app. Text and failed only walk
  the rows in `id` order up to the page's `LIMIT` (DEBT-0063). The query
  listens to `exchanges`, and to `sessions` when a filter joins it, so the
  window follows commits as with the generated ones.
- `observeIssues(filter)` follows the issues an `IssueFilter` (status, tag;
  unset matches all) matches, newest first, each with its sorted tags and
  linked exchange count, through one query that listens to the three issue
  tables; `observeIssueTags()` every tag in use, `issueExchanges(id)` the
  linked exchanges in arrival order. `issuePage(filter, beforeId, limit)`
  reads one page of the same query once, each issue with its linked
  exchange ids, in one transaction (`list_issues`, SDD 10).
- `searchExchangeSizes(filter, afterId, limit)` is the same search with
  each row's stored body sizes (`bodies.size` of both parts, 0 when the
  part has no row), read by two subqueries on the `bodies` key. A
  `CONNECTION` row has `sent_size` and `body_size` from its decoded
  `response_meta` instead, 0 until it closed (ADR-0104).
- Connections (ADR-0103, ADR-0104): `StoredExchange.isConnection` is
  `capture_layer = CONNECTION`; `ExchangeDetail.connectionBytes` holds its
  bytes sent and received, null for any other layer. Its endpoint is
  `CONNECT`, the host and `:port` as the path.
- `groupByEndpoint(filter, maxExchanges)` (`Endpoints.kt`, ADR-0097)
  pages through `searchExchangeSizes`, oldest first, at most 100 000 rows,
  and groups them per endpoint (`EndpointTally`: count, status classes,
  failed, error rate, `Durations` min, p50, p95, p99, max by nearest rank,
  stored body bytes), most called first; `EndpointGroups.truncated` when a
  matching row is left past the cap. `endpoint_stats`, `diff_runs` (SDD
  10) and the window's benchmarks (SDD 01) read through it; it only reads.
- `groupByEndpointAndDevice(runId, filter, maxExchanges)` (`Endpoints.kt`,
  ADR-0140 D) is the same paging over run `runId`, each row tallied under
  its endpoint and its session's device id (`DeviceGroups`,
  `DeviceTallies`); the run's sessions are read first and again when a row
  names a session that joined since. `RunDevice` names a device as its
  last `Hello` did. The window's "By device" reads it.
- `diffEndpoints(base, run)` (`EndpointDiff.kt`, ADR-0097) compares two
  `EndpointGroups` per endpoint: added, gone, changed (`EndpointChange`,
  the rule in SDD 10's *Changed*), unchanged as a count, truncated when
  either grouping was. `diff_runs` and the window's run diff use it; it
  reads nothing itself.
- The window's request list is the selected session's exchanges under the
  toolbar's search field and the filter sheet: method, status classes,
  failed only, hosts (ADR-0148). The sheet lists the hosts of the shown
  list (`observeHosts`: the distinct `exchanges.host` of a session, a run
  or every stored exchange, or of the live list), and a picked host gone
  from it, so it can be unpicked. The device
  and app are the session picked in the device list, the run the run
  picker; changing a filter starts again from the first page.

### Tests that drive it

Each case above, with the test that proves it (M5.3 unless noted):

| Case | Test |
| --- | --- |
| Directory and file created `0700` / `0600`, WAL inherits it | `StorageOpenTest.createsOwnerOnlyFiles` (POSIX only) |
| An existing directory keeps its mode, one the user cannot chmod still opens | `StorageOpenTest.keepsTheModeOfAnExistingDirectory`, `StorageOpenTest.opensInADirectoryItCannotChmod` (POSIX only; `/tmp` owned by root) |
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
| `flush`, `newRun`, `clearAll`, `applyRetention` and `setRetention` after close return | `WriteQueueTest.callsAfterCloseReturn` |
| A timed out close leaves the file open until the writer's batch ends, waiting calls return | `WriteQueueTest.timedOutCloseLeavesTheFileToTheWriter` |
| Retention and clear calls queued on the writer during a timed out close do nothing and return | `WriteQueueTest.callsQueuedOnTheWriterDuringATimedOutCloseDoNothing` |
| A read in flight keeps the database open until it returns, reads after close return nothing, observed queries end without an exception | `WriteQueueTest.aReadInFlightKeepsTheDriverOpenUntilItReturns`, `WriteQueueTest.observedQueriesEndWhenTheStoreCloses` (M5.R10) |
| Run summaries page newest first with sessions and counts; settings kept across launches, deleted by clear all, null once closing | `RunsTest.runSummariesPageNewestFirstWithSessionsAndCounts`, `RunsTest.settingsAreKeptUntilClearAll` |
| Data directory per OS and its override | `DataDirectoryTest` |
| Pages by key, stable while rows are added | `PagingTest.pagesByKeyWhileIngesting` |
| Observed pages and sessions follow commits | `PagingTest.observedPagesFollowCommits` |
| A 10k-request session listed in bounded time and not stored, a stored run one click away after a restart, the list reads its next page | `TrafficStoreTest` in `desktop/app` (M5.4, H3.1b) |
| The window on the live list: two devices in one list, a device alone, Clear empties it, no store row; stored runs read-only | `AppMonitorAppTest` in `desktop/app`, `live.png`, `runs.png` (H3.1b) |
| Approvals stored, reopened, forgotten | `ApprovalStoreTest` (M5.5), and in `desktop/app` across three launches with a real server |
| Sessions grouped by run, runs named | `RunsTest` (M5.6): a session spanning "New run" stays in its run, names kept across a restart, a lost "New run" keeps the earlier run |
| One case per filter and their combination, pages under a filter, observed search follows commits | `SearchTest` (M5.7) |
| Stored body sizes per row under a filter, 0 without a body | `SearchTest.sizesAreTheStoredBodyBytesUnderAFilter` (M6.5) |
| The `CONNECTION` fixtures: no status, byte counts in the detail and the sizes, text search, no status class; endpoints per host and port, a refused one as failed, the run diff | `ConnectionTest` (M8.4) |
| A mocked exchange: `mock_rule_id` in every list reader, its rule's name in the detail and null once deleted, a version-4 file migrates | `MockedExchangeTest` (M9.5); the list mark and detail line in `MockedExchangeUiTest` (`mocked.png`) |
| A replayed exchange: `replay_of` in every list reader, its original linked within the session only, a version-6 file migrates | `ReplayedExchangeTest` (M9.9); the list mark and link in `ReplayTest` (`replay.png`) |
| Breakpoints per app in order, enabled only, kept across a restart and retention, gone with "Clear all data", ids not reused, bad ones refused, a version-5 file migrates | `BreakpointsTest` |
| A gRPC call ended with a status other than OK (the adapter's headers): `grpc_status` in every list reader, an error to endpoint tallies and the run diff, the last header wins, a version-2 file migrates | `GrpcStatusTest` (M8.7); the proxy's real call in `Http2ProxyTest`; the list, detail and benchmarks in `GrpcStatusUiTest` |
| Each indexed filter keeps its index | `SearchTest.indexedFiltersUseTheirIndex` (M5.7), on `EXPLAIN QUERY PLAN` |
| Text and failed only over 100 000 exchanges, no match, bounded time | `SearchTest.unindexedFiltersOverManyExchangesInBoundedTime` (M5.7), prints the time |
| The filter sheet narrows the list by status class, host and method, and combines | `AppMonitorAppTest` in `desktop/app`, `filters.png` (M5.7, H3.6c) |
| The hosts of a run, a session or every stored exchange | `SearchTest.hostsOfARunASessionOrEveryExchange` (H3.6c) |
| The sheet offers the shown hosts, a host filter keeps only that host's rows | `TrafficViewModelTest` in `desktop/feature/traffic` (H3.6c) |
| Age bound, oldest session first, connected and named kept, empty runs and unused devices deleted, secure delete | `RetentionTest.ageBoundDeletesOldSessionsAndKeepsNamedRunsAndConnectedOnes` (M5.8) |
| Size bound, oldest first until under 90 %, the file shrinks, what is kept past the bound reported | `RetentionTest.sizeBoundDeletesOldestFirstUntilUnderNinetyPercent` (M5.8) |
| Settings kept across restarts, a pass at open | `RetentionTest.settingsKeptAcrossRestartsAndAppliedAtOpen` (M5.8) |
| "Clear all data" leaves one new run, no row and no deleted byte in the files | `RetentionTest.clearAllDeletesEverythingAndRewritesTheFile` (M5.8) |
| A pass stops when the bound is raised under it, and leaves a failed `clearAll()`'s error | `RetentionTest.aRaisedBoundStopsAPassAlreadyRunning`, `RetentionTest.aClearAllFailingDuringAPassKeepsItsError` (M5.R5) |
| The settings change retention and clear after a confirmation | `AppMonitorAppTest` in `desktop/app`, `settings.png` (M5.8) |
| The theme follows the system until chosen, is read back after a restart, and repaints the window | `StoredAppearanceTest` in `desktop/data`, `AppMonitorAppTest` "the theme chosen in the settings repaints the window and is stored" in `desktop/app`, `settings-dark.png` (H3.5) |
| A linked exchange kept past the age and the size bound with its body, the rest of its session deleted, released by deleting the issue | `IssuesTest.aLinkedExchangeIsKeptPastBothRetentionBounds` (M7.2) |
| An issue survives a reopen; edits change only their fields, in queue order; status and tag filters | `IssuesTest.anIssueSurvivesAReopen`, `IssuesTest.editsChangeOnlyTheFieldsTheyCarryInQueueOrder` (M7.2) |
| Unknown exchanges or issue write nothing; a lost batch or a closed store is not saved; "Clear all data" deletes issues; a version-1 file migrates | `IssuesTest` (M7.2) |
| "Save as issue" on ticked requests, the list filtered, an issue edited and deleted, kept across a reopen | `IssuesUiTest` in `desktop/app`, `issues.png` (M7.2) |
| Mock rules: active per app in order, pending until confirmed, kept across a reopen, a version-3 file migrates | `MocksTest` (M9.2), SDD 12 |
| Live list: two devices in one list, events as the store gives them, filters, Clear keeps connected sessions, both bounds drop the oldest, endpoints grouped | `LiveTrafficTest` (H3.1) |
| Record: nothing stored without a recording, every device in the run whatever when it connected, what started while recording ends in the run after Stop, a session spanning two recordings, `clearAll` ends it and opens no run | `RecorderTest` (H3.2); Record and Stop in the window, the run opened read-only, in `AppMonitorAppTest` (`recording.png`) |
| A run per endpoint and device: one column per device in the order it joined, a device reconnected during the run counted once, what came before Record left out, the filter applied, its own run id ignored | `StoredRepositoriesTest` in `desktop/data` (H3.3); the window's "By device" in `BenchmarksUiTest` (`devices.png`) |
| Save: live exchanges into one "Saved" run with their bodies, frames and failure, not written twice, kept across a restart and by retention, one gone from the list left out; an issue from live requests links their saved rows | `SaveTest` (H3.2b); `LiveRepositoriesTest` in `desktop/data`; Save in the window, "Saved" opened read-only, in `AppMonitorAppTest` (`saved.png`) |
| Live list behind the repositories: no store row of it, a run or stored id reads the store, the rule name from the store; MCP tools read it without `run_id` | `LiveRepositoriesTest` in `desktop/data`, `LiveTrafficToolsTest` in `desktop/mcp` (H3.1) |

## Limits

- Not encrypted: owner-only permissions are the protection (ADR-0081 C.1).
- One desktop per data directory: a second instance on the same directory
  is not detected (DEBT-0062); `APPMONITOR_DATA_DIR` separates them.
- A batch lost to a write failure is not retried.
