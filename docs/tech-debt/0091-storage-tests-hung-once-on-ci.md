---
id: DEBT-0091
title: Desktop storage tests hung once on CI
status: Open
date: 2026-10-25
trigger: "`:desktop:storage:test` (`desktop/storage/src/test/`) hangs or times out on CI a second time"
paid_date:
---

# DEBT-0091 — Desktop storage tests hung once on CI

## What is missing

In the `check` job of PR #265, run 40213226203, on ubuntu,
`:desktop:storage:test` started at 16:51:55 UTC. Every other test task
finished by 16:53:01; `:desktop:storage:check` never did, and the job was
cancelled at its 30-minute `timeout-minutes` (`.github/workflows/ci.yml`).
The log prints nothing from the tests after the task starts. The PR does
not touch `desktop/storage`. In green runs the whole build takes about
3 minutes. No test report exists: the `test-reports` upload runs on
`failure()`, and a timeout cancels the job instead.

The cause is not known. The storage tests use one writer thread per store
(`WriteQueue`) and `close` with a bound. A store not closed, or a `flush`
awaiting a writer that died, could wait for ever.

## Why it was accepted

One sample, with no report and no stack: there is nothing to tell which
test waited, and a guess fixed blind would hide the second sample. The
failed job was re-run once.

## Trigger

`:desktop:storage:test` hangs or times out on CI a second time (tests in
`desktop/storage/src/test/kotlin/appmonitor/desktop/storage/`). Before
anything else, make the next one leave evidence: upload `test-reports`
on `cancelled()` too, or give the storage `Test` task a timeout and a
thread dump, then decide from the stack.

## How it was paid

> Filled when closing.
