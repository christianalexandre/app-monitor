---
id: DEBT-0065
title: The retention settings UI test timed out once on CI
status: Open
date: 2026-10-24
trigger: "`AppMonitorAppTest`'s retention settings test (`desktop/app/src/test/kotlin/appmonitor/desktop/app/AppMonitorAppTest.kt`) fails on CI a second time"
paid_date:
---

# DEBT-0065 — The retention settings UI test timed out once on CI

## What is missing

`the settings change retention and clear all data after a confirmation`
timed out at its first `awaitText("Pixel")` (`ComposeTimeoutException`,
`TIMEOUT_MS` 10 s) in the `check` job of PR #187, run 40141503880, on
ubuntu. The rerun of the same commit passed, as did `make check` on the
owner's MacBook Pro. The cause is not known: the session row is written by
the storage writer and read back through a `Flow`, and a slow runner may
need longer than the timeout, or an emission may be missed.

## Why it was accepted

One failure in the CI history, on a change that does not touch
`desktop/app` (M5.R4 is inside `Ingest`): there is no second sample to
tell a slow runner from a missed emission, and raising the timeout blind
would hide the second.

## Trigger

`AppMonitorAppTest`'s retention settings test
(`desktop/app/src/test/kotlin/appmonitor/desktop/app/AppMonitorAppTest.kt`)
fails on CI a second time. Then keep the test report and the timing of
the first `awaitText`, and decide between a longer bound and a fix.

## How it was paid

> Filled when closing.
