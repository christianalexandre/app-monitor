---
id: DEBT-0051
title: Non-correctness findings of the third M3 milestone review
status: Paid
date: 2026-10-24
trigger: The next PR that touches `samples/android/src/main/AndroidManifest.xml`, or a rotation or process-death regression in the Android sample
paid_date: 2026-11-04
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
regression found by hand, pays the coverage item.

## How it was paid

Partly paid: the line wrapping of the Android end to end paragraph in SDD 02
and of the M3.R5 task line, in `fix/android-sample-extras-per-target`.

The trigger item, in `feat/kmp-sample-app`: DEBT-0050's `Makefile` item was
paid there, and the `Makefile` left its trigger.

Paid by PLAN-0016 H6.6: `make e2e-android` rotates the emulator there and back with no new session
(M3.R3), then kills the process from the background and reopens the task, one new session and no
call run again (M3.R5). It waits for `mCurrentRotation`, so a rotation that did not happen fails. The
server is not advertised (`--advertise false`): an API 37 emulator found it by mDNS without the extras,
and the check passed with M3.R5's fix undone. Each fix undone now fails its line.
