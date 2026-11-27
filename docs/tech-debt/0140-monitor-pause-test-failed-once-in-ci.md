---
id: DEBT-0140
title: The title bar's pause test failed once in CI
status: Open
date: 2026-11-27
trigger: The second failure of `MonitorPauseTest` in CI or on a developer machine, or the next PR that touches `MonitorPauseTest.kt`
paid_date:
---

# DEBT-0140 — The title bar's pause test failed once in CI

## What is missing

`desktop/app/src/test/kotlin/appmonitor/desktop/app/MonitorPauseTest.kt`
`the title bar pauses and resumes the monitor` failed once in the release gate's
`desktop (macos-latest)` job on 2026-11-27 (run 45922484911, on #446): an
`AssertionError` from `SemanticsNodeInteraction.kt:178`, caused at
`MonitorPauseTest.kt:105`. The desktop code had passed the same job in the
previous gate run; the rerun of the failed jobs passed.

## Why it was accepted

One failure, not reproduced: on a developer's Mac the test passed five runs in a
row alone and the full `:desktop:app:test` (120 tests) passed. Deciding whether
the wait or the lookup is wrong needs a second occurrence, not a guess in a
release.

## Trigger

The second failure of `MonitorPauseTest` in CI or locally, or the next PR that
touches `MonitorPauseTest.kt`. Then: keep the failure's full message and the
screenshot, and wait for the title bar's switch before clicking it.

## How it was paid

> Filled when closing.
