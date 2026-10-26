---
id: DEBT-0065
title: Settings UI test timed out once on CI
status: Open
date: 2026-10-24
trigger: "A test of `AppMonitorAppTest` (`desktop/app/src/test/kotlin/appmonitor/desktop/app/AppMonitorAppTest.kt`) fails on CI again"
paid_date:
---

# DEBT-0065 — Settings UI test timed out once on CI

## What is missing

`the settings change retention and clear all data after a confirmation`
timed out at its first `awaitText("Pixel")` (`ComposeTimeoutException`,
`TIMEOUT_MS` 10 s) in the `check` job of PR #187, run 40141503880, on
ubuntu. The rerun of the same commit passed, as did `make check` on the
owner's MacBook Pro. The cause is not known: the session row is written by
the storage writer and read back through a `Flow`, and a slow runner may
need longer than the timeout, or an emission may be missed.

A second test, `the run list switches between named runs and all of
them`, failed twice, in the `check` job of PR #188 (run 40142472448) and
of PR #203 (run 40153393931), both on ubuntu, with the same report:
`onNodeWithText("iPhone").assertDoesNotExist()` at line 333, after "New
run", found the iPhone session. Line 303, noted here before, was the
`runDesktopComposeUiTest` call the stack also names. The race: "New run"
shows the current run at once, the second one until the new run is
written, and the iPhone session's first page loads empty, so
`awaitText("No requests yet")` could pass before the new run showed. PR
#203 waits for the iPhone session to go instead. Only the settings timeout
is left, with one sample.

## Why it was accepted

One failure in the CI history, on a change that does not touch
`desktop/app` (M5.R4 is inside `Ingest`): there is no second sample to
tell a slow runner from a missed emission, and raising the timeout blind
would hide the second.

## Trigger

A test of `AppMonitorAppTest`
(`desktop/app/src/test/kotlin/appmonitor/desktop/app/AppMonitorAppTest.kt`)
fails on CI again. Re-triggered by PR #203: the run list failures were one
race, fixed there; the settings timeout still has one sample. Then keep the
test report (the `test-reports` artifact, line and node of the failed
assertion) and decide between a longer bound and a fix.

Fired again by #293's release gate re-runs on `windows-latest` (run
40421416933): the same test timed out, this time at line 432, waiting for
the stored retention to be 30 days and 2 GiB. Cause found and fixed in #295:
the dialog built each choice from the retention it showed, so a second
choice made before the store echoed the first one wrote the first one back
(`Keep` lost). `SettingsViewModel.changeRetention` now applies each change
to the newest value, chosen or stored (`SettingsViewModelTest`, "a second
retention choice keeps the first one…", fails with the old base); the test
also waits for each menu item before clicking it. The first timeout, at
`awaitText("Pixel")`, has not shown again: the DEBT stays open on it.

## How it was paid

> Filled when closing.
