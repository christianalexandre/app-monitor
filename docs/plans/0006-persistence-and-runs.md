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
- [ ] **M5.7 Search and filters.** Over the stored exchanges: text in URL
  and host, method, status class, failed only, device and app, run;
  answered by indexed queries, not by filtering in memory.
  Accept: `./gradlew :desktop:storage:test` with one case per filter and
  their combination; `./gradlew :desktop:app:test` with a filtered
  screenshot.
- [ ] **M5.8 Retention settings.** Per M5.1 (b): the settings in the UI,
  applied at startup and while the app runs, clear everything.
  Accept: `./gradlew :desktop:storage:test` with a controlled clock and
  size, the oldest data deleted first and what M5.1 (b) keeps kept.
- [ ] **M5.9 Live check.** `liveScreenshots` (ADR-0031) and a hand run of
  a sample against `make desktop` across a restart; the packaged app
  (`packageDmg`) opens its database.
  Accept: `docs/screenshots/` updated; the packaged app run shown in the
  PR.
- [ ] **M5.10 Docs sync.** README status, SDD 01 and 03 (the planned
  `desktop/storage` moves to "Today"), `AGENTS.md` commands.
  Accept: `make docs-check`; every command the milestone added is in
  `AGENTS.md`.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
