---
id: DEBT-0070
title: The observed queries close test can close before its collectors read
status: Paid
date: 2026-10-24
trigger: "The first PR that touches `desktop/storage/src/test/kotlin/appmonitor/desktop/storage/WriteQueueTest.kt` or `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Storage.kt`, or the test failing on CI a second time"
paid_date: 2026-10-24
---

# DEBT-0070 — The observed queries close test can close before its collectors read

## What is missing

`WriteQueueTest.observedQueriesEndWhenTheStoreCloses` failed with
`ended without an exception` (`WriteQueueTest.kt:398`) in the `check` job of
PR #198, run 40149677924, on ubuntu. That PR changes only docs. The test
starts its two collectors with a plain `async` and closes the store once a
third collector sees the session; nothing waits for the two to have read
once, so a close that wins leaves them with an empty list, which the
assertion reads as a failure.

## Why it was accepted

The failure is in the test's ordering, not in a wrong result of `Storage`:
the same test asserts that a flow collected after close ends at once and
empty. Fixing it is a code change, outside a docs PR.

## Trigger

The first PR that touches
`desktop/storage/src/test/kotlin/appmonitor/desktop/storage/WriteQueueTest.kt`
or `desktop/storage/src/main/kotlin/appmonitor/desktop/storage/Storage.kt`,
or the test failing on CI a second time. Then wait for each collector's
first list before `close`.

## How it was paid

PLAN-0007 M6.3: the test waits, with a timeout, for each collector's first
list before it closes the store.
