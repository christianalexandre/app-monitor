---
id: ADR-0139
title: The window shows live traffic by default; a run is a recording started and stopped by hand
status: Superseded # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by: ADR-0140
---

# ADR-0139 — The window shows live traffic by default; a run is a recording started and stopped by hand

## Context

The owner used the desktop with the Android sample on 2026-10-25 and
asked for (their words, translated):

- A device cannot be disconnected once connected.
- A connection belongs to one run: after "New run" the device's traffic
  stays in the previous run. This is ADR-0084 A.1 as built — a session
  joins the run current when it connects (SDD 09, "Each `Connected`
  keeps the run current when the writer reached it").
- The default should be what the owner's earlier tool,
  `app-network-monitor`'s macOS client, does: every request is printed
  as it arrives and the user clears the list. Its `DashboardView.swift`
  has one list of every device's requests, a trash button
  (`viewModel.clearLogs`), a filter sheet by status class and host, and
  a "N devices connected" badge.
- Runs stay, but not as the default: starting a run records requests
  until it is stopped, and a run keeps every connected device so the
  devices can be compared with each other.
- Light and dark mode, and a layout pass ("finesse") taking
  `app-network-monitor` and RocketSim (rocketsim.app: one realtime
  timeline, a detail with headers, body, metrics and cURL, copy and
  export actions in the toolbar) as references.

This is product scope; the owner decided it in the conversation and
asked the agent to carry it out. ADR-0084 A.1 and C.1 are replaced; its
B.1 (unnamed by default, named at any time) stays.

## Options

1. **Live list with Clear; runs as recordings** — the owner's request.
2. **Keep ADR-0084 and fix only the session pinning** — leaves runs as
   the default, which the owner rejected.

## Decision

Option 1.

- **A. Live by default.** The window opens on the live list: every
  exchange of every device, newest last, since the last **Clear**.
  Clear hides what is in the list now (a cleared-at mark kept in the
  store); it deletes nothing, so a recording and retention are not
  affected. No run opens at launch.
- **B. A run is a recording.** "Record" starts a run, "Stop" ends it;
  at most one is recording. An exchange belongs to the run that was
  recording when it **started**, whatever its session: a device
  connected before Record, or that reconnects during it, is in it. A
  run lists every device that sent an exchange in it. Runs are listed
  apart from the live list, opened read-only, named as in ADR-0084 B.1,
  and kept past retention when named (ADR-0082 C).
- **C. Compare devices in a run.** A run's view groups its exchanges by
  endpoint (as benchmarks do) with one column per device: count,
  status classes, median and p95 time, size. Run-to-run diff stays.
- **D. Disconnect.** A device in the list has "Disconnect": the desktop
  closes its session and stops accepting it, without a new approval
  prompt, until "Reconnect" in the same list. No SDK change: the SDK's
  backoff keeps retrying and is refused as an unapproved device is
  today (ADR-0083).
- **E. Light and dark.** The theme follows the system, with a setting
  for System / Light / Dark. Every color is a token with both values in
  `desktop/designsystem`; screenshots are taken in both.
- **F. Layout.** Three panes (devices, requests, detail) with a toolbar
  holding Record/Stop, Clear, filters and search; a request row shows a
  colored status badge, method, URL truncated in the middle, time and
  duration; the detail has tabs (Summary, Headers, Body, Timing, cURL)
  and copy actions. Details follow `app-network-monitor`'s client and
  RocketSim, and ADR-0133's references.
- **MCP.** Tools that took "the current run" read the live list
  (since the last Clear) unless given a run id; `list_runs` lists
  recordings.

## Consequences

- A storage migration: a run gets its exchanges by start time, not by
  session, and a cleared-at mark is kept.
- Run diff and benchmarks keep working on a run; benchmarks also work
  on the live list.
- The owner reviews the layout from screenshots in light and dark at
  the end of the plan.
