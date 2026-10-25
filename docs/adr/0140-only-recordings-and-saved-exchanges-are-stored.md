---
id: ADR-0140
title: Live traffic stays in memory; only recordings and what the user saves are stored
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-10-25
superseded_by:
---

# ADR-0140 — Live traffic stays in memory; only recordings and what the user saves are stored

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

Right after ADR-0139, the owner added: only runs, or what the user
actively chooses to save, should go to the database, not every request;
storing everything pollutes the history and fills the disk fast. ADR-0139
A kept every exchange in the store behind a cleared-at mark, and ADR-0081
stores all traffic; both are replaced here. This ADR restates ADR-0139
in full with that change and supersedes it.

This is product scope; the owner decided it in the conversation and
asked the agent to carry it out. ADR-0084 A.1 and C.1 stay replaced; its
B.1 (unnamed by default, named at any time) stays.

## Options

1. **Live in memory; store recordings and saved exchanges** — the
   owner's request.
2. **Store everything, hide it behind Clear** (ADR-0139 A) — rejected by
   the owner.

## Decision

Option 1.

- **A. Live in memory.** The window opens on the live list: every
  exchange of every device, newest last, since the last **Clear**. The
  live list is held in memory only, bounded (oldest dropped first, the
  bound in the SDD), and gone on quit. Clear empties it. Nothing of it
  is written to disk.
- **B. A run is a recording, and is stored.** "Record" starts a run,
  "Stop" ends it; at most one is recording. Every exchange that
  **starts** while recording is written to the store in that run,
  whatever its session: a device connected before Record, or that
  reconnects during it, is in it, with its session's device and app
  rows. Runs are listed apart from the live list, opened read-only,
  named as in ADR-0084 B.1. Retention (ADR-0082) applies to runs only.
- **C. Save by hand.** From the live list, the user can save selected
  exchanges ("Save"); they are stored in a "Saved" collection listed
  with the runs. Attaching an exchange to an issue, from the window or
  MCP, saves it. Mock rules, approvals and settings are stored as today.
- **D. Compare devices in a run.** A run's view groups its exchanges by
  endpoint with one column per device: count, status classes, median
  and p95 time, size. Run-to-run diff stays.
- **E. Disconnect.** A device in the list has "Disconnect": the desktop
  closes its session and refuses it, without a new approval prompt,
  until "Reconnect". No SDK change: the SDK's backoff keeps retrying and
  is refused as an unapproved device is today (ADR-0083).
- **F. Light and dark.** The theme follows the system, with a setting
  for System / Light / Dark; every color is a token with both values in
  `desktop/designsystem`; screenshots in both.
- **G. Layout.** Three panes (devices, requests, detail) with a toolbar
  holding Record/Stop, Clear, Save, filters and search; a request row
  shows a colored status badge, method, URL truncated in the middle,
  time and duration; the detail has tabs (Summary, Headers, Body,
  Timing, cURL) and copy actions; follows `app-network-monitor`'s
  client, RocketSim and ADR-0133's references.
- **MCP.** Tools read the live list by default and a run or "Saved" when
  given its id; `list_runs` lists recordings and the saved collection.
  An agent's search reaches only what is live or stored.

## Consequences

- `desktop/data` serves the live list from memory and runs from the
  store behind the same repositories (ADR-0133), so features and MCP do
  not change their reads.
- A storage migration: existing runs and their exchanges are kept as
  recordings; nothing else is written from now on.
- A desktop restart loses the live list; the user records or saves what
  must survive.
- The owner reviews the layout from screenshots in light and dark at
  the end of the plan.
