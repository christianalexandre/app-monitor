---
id: DEBT-0051
title: Non-correctness findings of the third M3 milestone review
status: Open
date: 2026-10-24
trigger: The next PR that touches `samples/android/src/main/AndroidManifest.xml` or `0050-m3-review-round-2-cleanups.md`, or a rotation or process-death regression in the Android sample
paid_date:
---

# DEBT-0051 — Non-correctness findings of the third M3 milestone review

## What is missing

Findings of the `code-review` of `de0d069..b3f1f69` that are not wrong
results, crashes, hangs, leaks, races or privacy issues for anything run
today (ADR-0016):

- `samples/android`: no automated check covers rotation or process death.
  `make e2e-android` launches cold with `am start -S`, so M3.R3 and M3.R5
  rest on manual `am kill` runs; a change of launch mode or a second
  activity can regress them unseen.
- DEBT-0050's trigger names `Makefile`, which most PRs that add a command
  touch, while only its stale ADR-0051 item is about the `Makefile`. Name
  the `debt-check` target's line or move the item's trigger elsewhere.

Rejected: `MainActivity` has no `onNewIntent`, but with the default launch
mode an `am start` that reaches a running top activity only brings its task
to the front, and neither `onCreate` nor `onNewIntent` runs; the end to end
uses `--activity-clear-task`. The plan's "Seven went to DEBT-0050" counts
`debt_check.sh`'s basename and path matching as two findings in one bullet.

## Why it was accepted

None of them gives a wrong result today; they are missing coverage, a noisy
trigger and line wrapping.

## Trigger

The next PR that touches `samples/android/src/main/AndroidManifest.xml`
(a launch mode or a second activity), or a rotation or process-death
regression found by hand, pays the coverage item; the next PR that touches
`0050-m3-review-round-2-cleanups.md` pays the trigger item.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.

Partly paid: the line wrapping of the Android end to end paragraph in SDD 02
and of the M3.R5 task line, in `fix/android-sample-extras-per-target`.
