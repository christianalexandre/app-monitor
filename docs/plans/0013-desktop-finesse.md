---
id: PLAN-0013
title: Desktop finesse — live traffic, recordings, themes and layout
status: Active # Draft | Active | Done | Abandoned
milestone: H3
started: 2026-10-25
finished:
---

# PLAN-0013 — Desktop finesse (H3)

## Goal

The desktop works the way the owner expects after the first field test
(ADR-0140): live traffic of every device with Clear by default, runs as
recordings that hold every device and compare them, a device can be
disconnected, light and dark themes, and a layout polished after
`app-network-monitor`'s macOS client and RocketSim.

## Tasks

Built on ADR-0133's modules: each task changes `desktop/domain`,
`desktop/data` and the feature it touches, never a screen reaching the
store. Every UI task adds or updates screenshots in light and dark. A
task too large for one PR is split at once.

- [ ] **H3.1 Live list in memory and Clear.** The window opens on every
  device's exchanges held in memory (bounded), with Clear; no run opens
  at launch and nothing of the live list is written to disk; MCP reads
  the live list by default. `desktop/data` serves it behind the existing
  repositories.
  Accept: `./gradlew :desktop:data:test :desktop:feature:traffic:test
  :desktop:mcp:test :desktop:storage:test` — traffic from two devices
  shows in one list, Clear empties it, the store gets no exchange row
  while nothing is recording, the bound drops the oldest.
- [ ] **H3.2 Runs as stored recordings, and Save.** Record / Stop: every
  exchange that starts while recording is stored in the run, whatever
  its session; existing runs kept by a migration; runs and "Saved"
  listed apart and opened read-only; Save on selected live exchanges;
  an issue saves its exchanges.
  Accept: storage, data and traffic tests — a device connected before
  Record and one that reconnects during it are both in the run; after
  Stop new traffic is only in memory; a saved exchange survives a
  restart.
- [ ] **H3.3 Compare devices in a run.** Endpoint by device columns
  (count, status classes, median, p95, size) in `insights`.
  Accept: `./gradlew :desktop:feature:insights:test` — a run with two
  devices shows both columns with their numbers.
- [ ] **H3.4 Disconnect and reconnect a device.** ADR-0140 E.
  Accept: `./gradlew :desktop:server:test :desktop:feature:traffic:test`
  — a disconnected device's reconnects are refused without a prompt
  until Reconnect.
- [ ] **H3.5 Light and dark.** Full token set with both values, System /
  Light / Dark in the settings.
  Accept: `make check` (the design system script) and the gallery and
  screen screenshots in both themes.
- [ ] **H3.6 Layout finesse.** ADR-0140 G: three panes, toolbar, request
  row, detail tabs with copy actions, filter sheet, empty states.
  Accept: `./gradlew :desktop:app:test` — screenshots of every screen
  in light and dark listed in the PR for the owner's review.

## Outcome

> Filled when the plan closes (`status: Done` or `Abandoned`):
>
> - **Shipped**: PRs merged (`#NN`) and what each delivered.
> - **Decisions**: ADRs created.
> - **Debt**: DEBTs created or paid.
> - **Deviations**: tasks added, split, dropped or reordered, and why.
> - **Lessons**: what the next plan should do differently.
