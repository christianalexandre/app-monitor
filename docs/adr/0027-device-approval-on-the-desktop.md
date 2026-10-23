---
id: ADR-0027
title: Device approval as a server hook, shown in the window
status: Accepted
date: 2026-10-22
superseded_by:
---

# ADR-0027 — Device approval as a server hook, shown in the window

## Context

ADR-0018 E decided *what* approval is: per (`device_id`, `app_id`), before
`HelloAck`, no timeout while the user decides, reject closes with 1008,
decisions kept in memory until the desktop app quits (persisted in M5).
This ADR decides *where* it lives and the edge cases ADR-0018 left open.

Already in place:

- `AppMonitorServer` publishes a session under the lock `stop()` takes,
  then sends `HelloAck` (SDD 01). The 10 s timeout covers only the first
  frame.
- The SDK sends `Hello` and nothing else until `HelloAck`, and waits for it
  without a timeout (SDD 04, `MonitorClient.serve`).
- The headless server (`make server`) and `tools/fake-client` have no UI.
- Ktor's `incoming` closes when the peer closes or the ping times out; a
  `select` over `incoming.onReceiveCatching` takes at most one frame, so
  none is lost to the race (kotlinx.coroutines `select` docs).

## Options

### Where the decision is made

1. **A `DeviceApproval` hook on `AppMonitorServer`**, `suspend (Hello) ->
   Boolean`, default "approve all"; the app passes a `DeviceApprovals`
   that remembers decisions and exposes `pending` as a `StateFlow`. The
   server stays UI-free and testable with a real socket; the headless
   server keeps working unchanged.
2. **Publish pending sessions on `sessions` and let the app send
   `HelloAck`** — the app would own protocol frames and the stop race the
   server already solves.

Chosen: 1.

### What a pending connection may do

1. **Any frame before `HelloAck` closes with 1002** ("Envelope before
   HelloAck") — no channel is accepted yet, so it is the same rule as an
   envelope on a non-accepted channel (ADR-0021). The SDK never does it.
2. **Buffer frames until approval** — memory held for a device nobody
   admitted.

Chosen: 1. A pending device that closes, or whose ping times out, is
withdrawn: the dialog entry disappears when its last connection leaves.
Two connections with the same key share one entry and one decision.
`stop()` during approval ends the connection (the engine cancels its
handlers); a decision after `stop()` began gets 1001 as before.

### How the app shows it

1. **An in-window panel over the three panes**, one request at a time
   ("<device_name> — <app_id> wants to connect", OS, app version, device
   id, "N more waiting"), Approve / Reject.
2. **A Compose `Dialog` window** — a second OS window that the offscreen
   UI test cannot drive as part of the main scene.

Chosen: 1. The dialog shows `app_id`, as ADR-0018 E specifies, so
DEBT-0024 (app name not sent) is not triggered.

## Decision

`AppMonitorServer(config, approval)` asks `approval` after a valid `Hello`;
the desktop app passes `DeviceApprovals` and renders its `pending` as an
in-window panel. Frames before `HelloAck` close with 1002. The headless
server and every existing caller approve all.

## Consequences

- The approval rule is tested at the socket level in `desktop/server`, the
  panel with the fake client in `desktop/app`.
- `make fake-traffic` against the desktop app waits only 10 s for
  `HelloAck`: the user must click Approve within that (DEBT-0028).
- Decisions are lost when the app quits (ADR-0018 E, M5).
