---
id: DEBT-0127
title: Findings of the H7 milestone review that normal use does not hit
status: Open # Open | Paid | Dropped
date: 2026-11-04
trigger: "The next PR that touches a file its items name: `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/DataDirectoryLock.kt`, `desktop/feature/traffic/src/main/kotlin/appmonitor/desktop/feature/traffic/GzipBody.kt`, `desktop/feature/traffic/src/main/kotlin/appmonitor/desktop/feature/traffic/BodyFormat.kt`, PLAN-0018 H8.8's PR for `TrafficViewModel.kt`, `desktop/feature/mocks/src/main/kotlin/appmonitor/desktop/feature/mocks/MocksScreen.kt`, `scripts/e2e_android.sh`"
paid_date:
---

# DEBT-0127 — Findings of the H7 milestone review that normal use does not hit

## What is missing

`code-review` at `medium` of PLAN-0017 (`a2a9a00..f723404`) found these, read in the code
(PLAN-0017, *H7 milestone review*). Only item 5 was run to the end:

1. `DataDirectoryLock.kt`: the guard keys on `toRealPath()`, which keeps
   `/System/Volumes/Data/...` apart from its firmlinked path. A second open by the other path
   gets `OverlappingFileLockException`, not `DataDirectoryInUseException`, and closing that
   channel drops the first store's POSIX lock.
2. `TrafficViewModel.kt`: Copy opens the body again and may hex dump it on the view model's
   thread, the cost ADR-0199 accepted, with the rendered `DecodedBody` thrown away.
3. `BodyFormat.kt`: `hexDump` has no bound on its output: 16 MiB opened is about 82 MB of text,
   chunked and rescanned by each search keystroke.
4. `BodyFormat.kt`: `prettyJsonOrNull` catches `SerializationException` only, over up to 16 MiB
   of inflated text; deep nesting could raise an `OutOfMemoryError` in the render coroutine.
5. `GzipBody.kt`: `GZIPInputStream` reads an `IOException` in a later member's header as the
   stream's end, so a body cut or corrupted there is captioned as opened, not cut or broken. A
   small program on this JDK read both cases as a clean end.
6. `GzipBody.kt`: `saysGzip` reads the first `Content-Encoding` line only, so `gzip` then `gzip`
   on repeated lines opens one layer and is captioned as opened.
7. `MocksScreen.kt`: `decodedBody` is a second gunzip with its own magic check and bound, so one
   stream can open in the detail and stay compressed in the mock draft. ADR-0199 says "Mock
   this" keeps the sent bytes; it does not, and SDD 01 has the correct sentence.
8. `scripts/e2e_android.sh`: the trap restores `accelerometer_rotation` as found but sets
   `user_rotation` to 0, so an emulator locked in landscape ends in portrait.

## Why it was accepted

None is hit in normal use, per the rule of ADR-0094: 1 needs a second store in one process by
another path; 2 is under a second at the bound; 3 and 4 need a body opening to many times its
size, and a crafted one is aimed at one's own debug tool; 5 and 6 need multi-member gzip or
repeated header lines, not met so far; 7 needs a body cut or opening past 1 MiB;
8 is a test script. They are correctness findings left open, so the plan's close waits for the
owner (ADR-0019).

## Trigger

The next PR that touches `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/DataDirectoryLock.kt`,
`desktop/feature/traffic/src/main/kotlin/appmonitor/desktop/feature/traffic/GzipBody.kt`,
`desktop/feature/traffic/src/main/kotlin/appmonitor/desktop/feature/traffic/BodyFormat.kt`,
`desktop/feature/mocks/src/main/kotlin/appmonitor/desktop/feature/mocks/MocksScreen.kt` or
`scripts/e2e_android.sh`, for that file's item; item 2 at PLAN-0018 H8.8's PR, which reworks
each body's view and copy. H8.2's PR touched `TrafficViewModel.kt` for one state field, not the copy
path (re-triggered 2026-11-06). H8.3's and H8.7's PRs left the copy path as it was; H8.8's,
which cuts long JSON values in the view and keeps them whole in Copy, reworks it
(re-triggered 2026-11-07).

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
