---
id: ADR-0222
title: The monitor's pause leaves out what starts, and keeps the server and the sessions
status: Accepted
date: 2026-11-15
superseded_by:
---

# ADR-0222 — The monitor's pause leaves out what starts, and keeps the server and the sessions

## Context

PLAN-0020 H9.2. In the field test of `0.1.0`, the owner asked for a play and
stop for the monitor, as app-network-monitor's client has (2026-11-15). That
client's ▶/■ stops its server (`DashboardViewModel.toggleServer`), so devices
drop their connection until it starts again.

Here, the SDKs keep what they could not send in a buffer and reconnect with
backoff (ADR-0066). If the server stopped, Play would bring back what was
buffered during the stop. Mocks and breakpoints would also stop applying,
because they live in the session (SDD 12).

Every device envelope passes `TrafficStore`, which feeds the live list and
the recorder (`TrafficSink`, ADR-0142).

## Options

1. **Pause the list.** The server and the sessions stay. While paused,
   `TrafficStore` leaves out each request that starts, with every later event
   of its own. A request already running when the pause starts ends as usual.
2. **Stop the server**, as app-network-monitor does. Devices disconnect, and
   on Play they resend what they buffered.
3. **Ask the SDKs to stop capturing.** This is a protocol change and an SDK
   release. A stopped capture would also skip mocks.

## Decision

Option 1, the owner's pick.
- `MonitorPause` (`desktop/app`, a `MonitorRepository`) decides per envelope
  in `TrafficStore`, before the sink. So neither the live list nor a
  recording gets what started while paused.
- Held calls and replays still read every envelope, so breakpoints and
  replays work while paused.
- The title bar shows "Live" or "Paused" beside a dot, with ⏸ or ▶.
- The pause is not stored: each launch starts live.
- Envelopes of other channels, and network ones that carry no request id,
  pass.

## Consequences

- No backlog when resuming, and no reconnection. The status bar's device
  count does not change with a pause.
- A request that started while paused stays out after the resume, until its
  session ends. Its ids are kept per session until then.
- A breakpoint hit while paused still holds its call and opens the dialog,
  but the call does not show in the list.
