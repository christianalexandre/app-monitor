---
id: DEBT-0065
title: Offscreen UI tests failed once each on CI
status: Open
date: 2026-10-24
trigger: "A test of `AppMonitorAppTest` (`desktop/app/src/test/kotlin/appmonitor/desktop/app/AppMonitorAppTest.kt`) fails on CI a third time"
paid_date:
---

# DEBT-0065 — Offscreen UI tests failed once each on CI

## What is missing

`the settings change retention and clear all data after a confirmation`
timed out at its first `awaitText("Pixel")` (`ComposeTimeoutException`,
`TIMEOUT_MS` 10 s) in the `check` job of PR #187, run 40141503880, on
ubuntu. The rerun of the same commit passed, as did `make check` on the
owner's MacBook Pro. The cause is not known: the session row is written by
the storage writer and read back through a `Flow`, and a slow runner may
need longer than the timeout, or an emission may be missed.

A second test, `the run list switches between named runs and all of
them`, failed at line 303 (`onNodeWithText("Pixel").assertDoesNotExist()`
right after `awaitText("iPhone")`) in the `check` job of PR #188, run
40142472448, on ubuntu. The rerun passed, as did three local reruns and
`make check`. That PR changes only the retention pass in `desktop/storage`.

## Why it was accepted

One failure in the CI history, on a change that does not touch
`desktop/app` (M5.R4 is inside `Ingest`): there is no second sample to
tell a slow runner from a missed emission, and raising the timeout blind
would hide the second.

## Trigger

A test of `AppMonitorAppTest`
(`desktop/app/src/test/kotlin/appmonitor/desktop/app/AppMonitorAppTest.kt`)
fails on CI a third time. Re-triggered by PR #188: the second failure was a
different test, so neither has a second sample yet. Then keep the test
report and the timing of its first wait, and decide between a longer bound
and a fix.

## How it was paid

> Filled when closing.
