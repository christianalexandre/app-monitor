---
id: DEBT-0137
title: Findings of the H9 milestone review that normal use does not hit
status: Open # Open | Paid | Dropped
date: 2026-11-25
trigger: "The next PR that touches a file its items name: `desktop/feature/traffic/src/main/kotlin/appmonitor/desktop/feature/traffic/TrafficScreen.kt` (`FollowNewest`, the paused empty state), `TrafficViewModel.kt` (`replaysOf`, `replayed`), `RequestDetail.kt` (the cut values' links), `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/MonitorPause.kt`; or the owner reporting a list that jumps, a replay's \"Open\" on the wrong call, a cut value that does not open, a slow list with large bodies, or a selection lost in an opened value"
paid_date:
---

# DEBT-0137 — Findings of the H9 milestone review that normal use does not hit

## What is missing

`code-review` at `medium` of PLAN-0020 (`ce4ea7b..894fb65`) found these, each read in the code
here (PLAN-0020, *H9 milestone review*); none was run end to end:

1. `TrafficScreen.kt` `FollowNewest`: `atTop` also accepts `firstVisibleItemIndex == arrived`.
   If the effect runs before the list has measured the new rows, a list scrolled exactly
   `arrived` rows down with offset 0 is taken as at the top and jumps there (ADR-0221).
2. `TrafficViewModel.kt` `replayed`: `replaysOf` reads the list newest first since ADR-0221, so
   `firstOrNull` of the new replays is the latest one. When two replays of one request arrive
   before the flow emits, "Open" picks the second.
3. `RequestDetail.kt`: the chunk's `CodeLink`s are remembered by their spans and capture the
   `toggle` of the `opened` state that built them. A new `opened` with equal spans (clicking
   JSON while JSON is already the default) and a chunk item that is not disposed in between
   leaves links that write to a dead state.
4. `MonitorPause.kt` `admits`: `requestIdOf` decodes every network envelope, bodies included,
   to read its request id, even when the monitor was never paused. Ingest decodes it again.
5. `TrafficStore.kt`: breakpoints and replays see an envelope before the pause filter. A call
   held while paused has no row, and a replay asked before pausing never brings back "Open".
6. `Code.kt` / `RequestDetail.kt`: the link covers the whole opened value, so a click to place
   the selection inside it (a double click on a word) folds it.
7. `TrafficScreen.kt`: while recording and paused, an empty list says "Paused", not that the
   recording takes nothing either; `shownRunId == null` there is redundant.

## Why it was accepted

None is hit in normal use, per ADR-0094's rule: 1 needs the effect before the layout and an
offset of exactly 0; 2 needs two replays of one request inside one store read; 3 needs a click
on the format already shown, and picking another request recreates the detail (`key(picked)`);
4 is a cost, not measured; 5 needs a pause between asking and receiving, or a breakpoint hit
while paused; 6 is a selection gesture, and Copy keeps the whole body; 7 is a caption.

A ninth finding was rejected: the `TrafficModule.kt` KDoc line over the line length, which
`make check`'s `spotlessCheck` passed.

## Trigger

The next PR that touches the file of an item, for that item; or the owner reporting one of the
symptoms in the frontmatter.

## How it was paid

> Filled when closing.
