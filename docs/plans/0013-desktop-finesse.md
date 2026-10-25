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

- [x] **H3.1 Live list source.** `LiveTraffic` holds every device's
  exchanges in memory (bounded), with Clear; `desktop/data` serves it and
  the store behind the existing repositories (ADR-0141). Split from the
  original H3.1, which was too large for one PR.
  Accept: `./gradlew :desktop:storage:test :desktop:data:test
  :desktop:mcp:test` — traffic from two devices shows in one list, Clear
  empties it, the bound drops the oldest, the store gets no row of it,
  MCP tools read it when no run is given.
- [x] **H3.1b Live list in the window and Clear.** The device server and
  the proxy feed the live list, not the store; the window opens on it
  with Clear and "All devices"; MCP reads it by default. "No run opens at
  launch" moved to H3.2, where Record opens runs; "New run" is gone until
  Record.
  Accept: `./gradlew :desktop:feature:traffic:test :desktop:app:test
  :desktop:storage:test` — traffic from two devices shows in one list,
  Clear empties it, the store gets no exchange row while nothing is
  recording.
- [x] **H3.2 Record and Stop.** Record / Stop: every exchange that starts
  while recording is stored in the run, whatever its session (`Recorder`,
  ADR-0142); no run opens at launch; existing runs stay as they are (no
  migration needed: they are already rows of `runs`); `liveScreenshots`
  gets its reopen step back on a recording. Split from the original H3.2,
  which was too large for one PR.
  Accept: storage, data and traffic tests — a device connected before
  Record and one that reconnects during it are both in the run; after
  Stop new traffic is only in memory.
- [x] **H3.2b Save, and runs and "Saved" apart.** Save on selected live
  exchanges into a "Saved" collection (a migration marks it apart from
  recordings); runs and "Saved" listed apart and opened read-only; an
  issue saves its exchanges (until then "Save as issue" on live requests
  is refused).
  Accept: storage, data and traffic tests — a saved exchange survives a
  restart; an issue made from live requests keeps them. Built as one run
  marked `saved` (ADR-0143); MCP's `save_issue` saves live ids too.
- [x] **H3.3 Compare devices in a run.** Endpoint by device columns
  (count, status classes, median, p95, size) in `insights`.
  Accept: `./gradlew :desktop:feature:insights:test` — a run with two
  devices shows both columns with their numbers.
- [x] **H3.4 Disconnect and reconnect a device.** ADR-0140 E.
  Accept: `./gradlew :desktop:server:test :desktop:feature:traffic:test`
  — a disconnected device's reconnects are refused without a prompt
  until Reconnect. Held in memory (ADR-0144); disconnected devices get
  their own "Disconnected" section, since a session without exchanges
  leaves the live list; screenshot in light only until H3.5.
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
