---
id: ADR-0142
title: A Recorder sink stores what starts while recording, one session row per run
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0142 — A Recorder sink stores what starts while recording, one session row per run

## Context

ADR-0140 B: "Record" starts a run, "Stop" ends it; every exchange that
**starts** while recording is stored in that run, whatever its session,
with its session's device and app rows. Since H3.1b the device server
(`TrafficStore`) and the proxy (`ProxyRecording`) feed `LiveTraffic` only,
through `TrafficSink` (ADR-0141 A), and the store still opens a run at
each launch (SDD 09).

The store as built (SDD 09, `Ingest.kt`): a session joins the run current
when its `Connected` is written, keyed by its server session id, and its
row is inserted lazily with its first envelope. About 60 tests in
`desktop/storage`, `desktop/data`, `desktop/mcp`, `desktop/proxy` and
`desktop/app` feed `Storage` directly and read `storage.runId`, the run
opened at launch.

What has to be decided: where the "started while recording" choice is
made, how a session connected before Record gets a row in the run, and
what becomes of the launch run.

## Options

1. **A `Recorder` sink in front of both.** `Recorder` implements
   `TrafficSink`, feeds `LiveTraffic` every call, and the store only the
   envelopes of requests that started while `Storage.recording` was set
   (and other channels while recording). It reads only field 1
   (`request_id`, the same in the four events) with Wire's `ProtoReader`,
   skipping bodies. A session gets a row in a run with its first stored
   envelope, under an internal key `<session>@<run>` with the run given
   explicitly; `server_session_id` stays as sent. `Storage` gains
   `record()`, `stop()`, `recording`, and `open(startRun = false)` for the
   window. Ingest, the queue and every existing test stay as they are.
2. **Recording inside `Ingest`.** Every envelope goes through the write
   queue and the writer drops what is not recorded. No second decode, but
   every device envelope crosses the queue again (bounded by bytes, it
   makes sessions wait on a slow disk, DEBT-0022), which H3.1b removed.
3. **No launch run anywhere.** `Storage.open` stops opening a run. Cleaner,
   but `runId` goes and the ~60 tests that rely on it are rewritten for no
   change in behaviour.

## Decision

Option 1. The window opens the store with `startRun = false`: no run at
launch or after "Clear all data". Tests keep the default run per launch.

- A request's run is fixed by its `RequestStarted`; its later events
  follow it there after Stop, until the session ends.
- A session spanning two recordings has one row in each.
- `Recorder.forget()` runs after `clearAll()`, which ends the recording.
- `list_runs`' `current` is the run being recorded; `RunRepository` has
  `recording`, `record()`, `stop()` in place of `currentRun` and `newRun()`.

## Consequences

- Nothing is stored without a recording; the window, MCP and the proxy are
  unchanged in what they read.
- Each recorded session costs one key per recorded request in memory until
  it disconnects.
- `Storage.open` has a mode the tests do not use by default;
  `RecorderTest` and the app's Record test cover it.
