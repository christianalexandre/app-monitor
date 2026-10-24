---
id: PLAN-0006
title: Persistence and runs
status: Active # Draft | Active | Done | Abandoned
milestone: M5
started: 2026-10-24
finished:
---

# PLAN-0006 — Persistence and runs (M5)

## Goal

The desktop keeps what it captured across restarts in a `desktop/storage`
SQLDelight database (ADR-0005): devices, apps, sessions, exchanges, frames
and compressed bodies, grouped in runs the developer can name; the UI lists
them through paged queries with search and filters, approvals survive a
restart (ADR-0018 E, ADR-0027), and retention settings bound what stays on
disk.

## Tasks

M4's lessons applied: decisions in the "stop and ask" areas of `AGENTS.md`
come first, in one stop; the platform primitives the storage relies on (the
SQLite driver's native library and the data directory on macOS, Linux and
Windows, the packaged runtime's modules) are listed with their evidence in
an ADR before the code; the store's design, write path and failure cases are
written down with the tests that will drive them before the code (M3's
lesson); `make debt-check` decides how each fired DEBT is handled
(DEBT-0004 and DEBT-0022 fire on this milestone).

- [x] **M5.1 Storage privacy, retention and run decisions.** (ADR-0081, ADR-0082, ADR-0083, ADR-0084 `Accepted`: the owner took every recommendation) `Proposed`
  ADRs, one stop: (a) what the desktop writes to disk — every envelope
  field, bodies and frames as received (masked on the device unless
  `masking = false`, ADR-0034), where the file lives on each OS, its
  permissions, whether it is encrypted; (b) retention — the defaults (age,
  size or run count), what is deleted first, whether a named run is kept
  past them, and how the developer clears everything; (c) approvals
  persisted — what is stored per (`device_id`, `app_id`), how an answer is
  revoked; (d) what a run is for the developer — opened per session or by
  hand, named when, what the UI shows by default (the roadmap's "named
  runs").
  Accept: the ADRs merged by the owner with `status: Accepted`.
- [x] **M5.2 Storage design on paper.** (SDD 09, ADR-0085, DEBT-0062; DEBT-0022 kept open, re-triggered in M5.4 on the write queue's high-water mark) SDD chapter for `desktop/storage`:
  tables and indexes (devices, apps, sessions, runs, exchanges, frames,
  bodies apart, ADR-0005), the migration rule, the write path from
  `DeviceSession` to SQLite and the thread it runs on, what happens when a
  write is slow or fails (DEBT-0022: the collector must not stop reading
  pongs), paging for the UI; each case names the test that will drive it.
  ADR `Accepted` for the platform primitives: SQLDelight and driver
  versions from their official source, the driver's native library on
  each desktop OS, the data directory per OS, the runtime modules the
  packaged app needs (ADR-0055).
  Accept: `make docs-check`; every write path case has a named test.
- [x] **M5.3 `desktop/storage` module.** (ADR-0086; `DeviceSession.peerAddress` added as SDD 09 asks; `approvals` and `settings` tables in schema 1 without queries; `java.sql` joins the package with the app's dependency in M5.4) SQLDelight schema per M5.2,
  migrations verified at build time, an ingest API taking `Hello` and
  envelopes, bodies compressed in their own table, paged queries the UI
  needs.
  Accept: `./gradlew :desktop:storage:test` (in `make build`) with M5.2's
  cases, schema verification included.
- [x] **M5.4 Desktop app on storage.** (DEBT-0004 paid; DEBT-0022 re-triggered on `queueFullWaits`, 10k requests peaked at 2.5 MiB of 64; until M5.6 the device list shows every stored session, all runs, since ADR-0084 C.1's picker is M5.6; the storage-failure window has no "Clear all data" until M5.8; the hand run stopped at the approval dialog, no screen access for the agent, so a real restart was checked on the database and the UI restart by `TrafficStoreTest`, DEBT-0028 re-triggered on M5.5; M5.9 repeats it by hand) `TrafficStore` writes to
  `desktop/storage` and the UI reads its paged queries; a restart shows
  the earlier traffic; DEBT-0004 paid, DEBT-0022 paid or re-triggered per
  M5.2.
  Accept: `./gradlew :desktop:app:test` with a store test ingesting a
  10k-request session in a bounded time and an offscreen screenshot after
  a reopen; `make fake-traffic` against `make desktop`, quit, relaunch,
  the requests still listed.
- [x] **M5.5 Approvals persisted.** (approvals queued to storage like envelopes; "Settings" above the device list opens the "Devices" section until M5.8 adds retention to it; `AppMonitorServer.disconnect` closes a forgotten pair; DEBT-0028 decided: the fake client's fixed pair, approved once, is enough, trigger narrowed to unattended scripts) `DeviceApprovals` per M5.1 (c): an
  answered pair is not asked again after a restart, a rejected one is
  closed at once, the developer can revoke.
  Accept: `./gradlew :desktop:app:test` — a pair approved, the store
  reopened, the device let in with no prompt; revoked, asked again.
- [x] **M5.6 Named runs.** (run changes ride the write queue, so a session keeps the run it connected in; the picker opens a run list overlay, like the settings, rather than a dropdown; the UI test also renames, switches and opens a run) Runs per M5.1 (d): opened, named and switched
  in the UI, the list of runs, exchanges grouped under their run.
  Accept: `./gradlew :desktop:app:test` — storage tests for run grouping
  and an offscreen screenshot of the run list with two named runs.
- [x] **M5.7 Search and filters.** (ADR-0087, DEBT-0063; DEBT-0058 fired and narrowed to grouping or whole-URL match; the window's device and app filter is the session list, `ExchangeFilter` carries device, app and run for M6; the run picker got a test tag, the filter menus share its "▾") Over the stored exchanges: text in URL
  and host, method, status class, failed only, device and app, run;
  answered by indexed queries, not by filtering in memory.
  Accept: `./gradlew :desktop:storage:test` with one case per filter and
  their combination; `./gradlew :desktop:app:test` with a filtered
  screenshot.
- [x] **M5.8 Retention settings.** (ADR-0088: size is live pages, free pages handed back one `incremental_vacuum` statement at a time since the driver steps once; "Clear all data" empties the file in place with `VACUUM` instead of swapping the store, and in the failure window deletes the files and asks for a reopen; settings and approvals cleared with it; empty runs and unused devices deleted with their last session; DEBT-0062 did not fire, the port is still fixed) Per M5.1 (b): the settings in the UI,
  applied at startup and while the app runs, clear everything.
  Accept: `./gradlew :desktop:storage:test` with a controlled clock and
  size, the oldest data deleted first and what M5.1 (b) keeps kept.
- [x] **M5.9 Live check.** (`liveScreenshots` now uses an `ApprovalStore`, keeps its store in `-PliveDataDir` and ends with a `<name>-reopen.png` after reopening it; emulator (API 37, `10.0.2.2`) and iPhone 17 Pro simulator (mDNS) refreshed, no phone attached so `device-*.png` kept from M1; the packaged app, run from the mounted DMG on the simulator's store with `APPMONITOR_DATA_DIR`, let the approved device in with no prompt and stored its 7 exchanges in its own run, then reopened the 14 after a quit; no Screen Recording permission, so shown in the PR by its database rows, not a window capture) `liveScreenshots` (ADR-0031) and a hand run of
  a sample against `make desktop` across a restart; the packaged app
  (`packageDmg`) opens its database.
  Accept: `docs/screenshots/` updated; the packaged app run shown in the
  PR.
- [x] **M5.10 Docs sync.** (SDD 01's storage moved out of planned and its in-memory limit dropped; SDD 09's stale "built in part" line removed, it cited ADR-0066 by mistake; retention added to SDD 03 and the storage test command; no new command since M5.9, every one already in `AGENTS.md`) README status, SDD 01 and 03 (the planned
  `desktop/storage` moves to "Today"), `AGENTS.md` commands.
  Accept: `make docs-check`; every command the milestone added is in
  `AGENTS.md`.

### First milestone review

Findings of the `code-review` of `982f39b..055c62b`. Five are correctness
findings and became tasks. Four went to DEBT-0064 (the search re-reading
on every commit, the size-bound pass committing one session at a time,
four comment blocks over 4 lines, mappings written twice). None was
rejected.

- [x] **M5.R1 A pair forgotten while its approval lands keeps streaming.** (every connection is tracked from its `Hello`; `disconnect` removes it under the publishing lock, so a pending or answered handshake is closed and never published; the test forgets inside the approval)
  `AppMonitorServer.serve` adds the session to `open` only after
  `approve()` returns and the session is published, so a `disconnect(key)`
  (Forget, Forget all, Clear all data) in that window misses it: the
  device goes on streaming with no answer stored, and after Clear all data
  its traffic is dropped while it shows as connected. Register the session
  before it is published, or have `disconnect` also close the handshakes
  waiting on that pair.
  Accept: `./gradlew :desktop:server:test` with a test that forgets the
  pair between the answer and the `HelloAck` (fails before the fix); the
  session is closed.
- [x] **M5.R2 Opening a chosen data directory changes its permissions.** (only a directory the store creates is set `0700`; the chmod test uses `/tmp`, root-owned and world-writable on macOS and Linux, and skips where it is not)
  `Storage.createOwnerOnly` sets `rwx------` on every open, so
  `APPMONITOR_DATA_DIR=$HOME/Documents` makes the user's Documents
  owner-only, and a directory the user does not own (`/tmp`) makes
  `setPosixFilePermissions` throw, so the store never opens. ADR-0081 C.1
  says the directory is *created* `0700`: set it only on a directory the
  store creates.
  Accept: `./gradlew :desktop:storage:test` with a test opening an
  existing `0755` directory (mode unchanged) and one the user cannot
  chmod (store opens).
- [x] **M5.R3 Calls after or during `Storage.close` hang or race the
  writer.** (the writer stops at the next batch boundary and settles what it leaves, so waiting calls return; the driver is closed when the writer job completes, after `close` returned if it timed out; the two longer comment blocks touched were trimmed to 4 lines, the rest of DEBT-0064 waits for M5.R5) `newRun()` and `flush()` ignore `enqueue` returning false and
  await `done` forever, and items left in the channel when `close` times
  out are never completed; `WriteQueue.close` cancels the writer without
  joining it, so `driver.close()` can run while the writer thread is still
  inside a blocking JDBC write. Complete what the queue will never write,
  return at once once closed, and close the driver only after the writer
  has left its batch.
  Accept: `./gradlew :desktop:storage:test` with `newRun()` and `flush()`
  after `close` returning, and a `close` that times out on a stalled
  `beforeCommit` not closing the driver under the writer.
- [x] **M5.R4 A rolled back `Disconnected` leaves the session connected.** (the writer keeps a lost `Disconnected` and its connection, and applies it ahead of the next batch; a session connected and disconnected in the same lost batch is restored from that batch's `Connected`; CI's UI retention test timed out once and passed on rerun, DEBT-0065)
  `Ingest.apply` removes the connection and its row from memory before the
  transaction commits; when the batch rolls back nothing restores them,
  so the row keeps `disconnected_at_ms` NULL for the rest of the launch:
  the UI shows the session as connected and retention never prunes it.
  Keep the disconnect in memory only once committed, or retry it in the
  next batch.
  Accept: `./gradlew :desktop:storage:test` with a batch holding a
  `Disconnected` failing in `beforeCommit`, then a good batch: the session
  is stored as disconnected.
- [ ] **M5.R5 A retention pass runs on with settings changed under it.**
  `applyRetention` reads the settings once and `yield()`s between chunks
  on the writer thread, so `setRetention` and `clearAll` can run in the
  middle of a pass. A pass started with a 7-day bound goes on deleting
  after the developer raised it to 30 days or removed it (data the new
  settings keep is deleted), and its final status overwrites `clearAll`'s,
  hiding a `clearAll` failure.
  Accept: `./gradlew :desktop:storage:test` with a pass interleaved with
  a `setRetention` that raises the bound (nothing past the new bound is
  deleted) and with a failing `clearAll` (its error stays in
  `retentionStatus`).

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
