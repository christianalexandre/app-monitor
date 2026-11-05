---
id: DEBT-0091
title: Desktop storage tests hung twice on CI
status: Paid
date: 2026-10-25
trigger: "A test in `desktop/storage/src/test/` fails on CI with `TimeoutException` (its stack in the `test-reports` artifact)"
paid_date: 2026-10-31
---

# DEBT-0091 — Desktop storage tests hung twice on CI

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

A second sample, in the `check` job of PR #285, run 40241841456: every
other test task and `:desktop:app:check` finished, `:desktop:storage:test`
did not, and the job was cancelled at 30 minutes, again with no report.
The PR changes `Search.kt` and `Storage.kt` (host filter, `observeHosts`),
none of it on the writer's or `close`'s path; its storage tests pass
locally. So PR #285 makes the next one leave evidence: each storage test
fails after 2 minutes with `TimeoutException`, its interrupted stack in
its XML result; the `Test` task stops at 10 minutes; `ci.yml` uploads
`test-reports`, `build/test-results/` included, on `cancelled()` too.

## Why it was accepted

One sample, with no report and no stack: there is nothing to tell which
test waited, and a guess fixed blind would hide the second sample. The
failed job was re-run once.

## Trigger

A test in `desktop/storage/src/test/` fails on CI with `TimeoutException`.
Re-triggered by PR #285, which added the timeouts above. Then read the
test and its stack from the `test-reports` artifact and fix the wait it
names.

## How it was paid

Paid by PLAN-0015 H5.2g. The third sample left its stack (`check` job of
`dev`, run 41112636074, ubuntu): `WriteQueueTest.closeGivesUpAfterTimeout`
timed out after 2 minutes inside `runBlocking`, with the queue's own close
already past its 300 ms. `Storage.close` started with
`retentionJob?.cancelAndJoin()`, unbounded. The retention pass at open runs
on `Dispatchers.Default` and hops to the writer thread; when the test's
`holdWriter` reached the writer first, the pass was queued behind the held
writer, and the test releases it only after `close` returns. Cancelling a
coroutine queued on an executor completes only when the executor runs it,
so `close` waited for ever. In the app, the same wait kept `close` past its
`STOP_TIMEOUT` for as long as a batch or a retention chunk held the writer.

`close` now cancels the retention coroutine and waits for it within its
timeout. `WriteQueueTest.aRetentionPassOnTheWriterDoesNotHoldCloseBeyondItsTimeout`
stalls a retention chunk on the writer and closes with 300 ms: on the old
`close` it timed out at `Storage.kt:502`, the `cancelAndJoin`; it passes
with the fix. The race itself is narrow: on
the owner's MacBook (10 cores) with ten `yes` processes running, the old
`closeGivesUpAfterTimeout` passed 25 of 25. With the fix, `WriteQueueTest`
passed 50 of 50 under the same load.
