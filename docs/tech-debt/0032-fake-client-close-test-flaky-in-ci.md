---
id: DEBT-0032
title: The fake client's "desktop closes with an error" test failed once in CI
status: Paid # Open | Paid | Dropped
date: 2026-10-23
trigger: The second CI failure of `MainTest > a desktop that closes the session with an error exits 1`, or any failure of it on a developer machine
paid_date: 2026-10-30
---

# DEBT-0032 — The fake client's "desktop closes with an error" test failed once in CI

## What is missing

`tools/fake-client` `MainTest > a desktop that closes the session with an
error exits 1` failed in CI run 39974647564 (PR #83, a change that does not
touch the fake client) with an `AssertionFailedError` reported at
`MainTest.kt:75`. The log does not say whether the exit status or the
one-line error was wrong. It passed on every earlier CI run, five times in a
row locally with `--rerun`, and on the rerun of the failed job.

## Why it was accepted

One failure without its assertion message is not enough to find a cause;
the likely suspect, the close frame racing the socket close in
`ackThenClose`, is a guess. Guessing a fix would hide the evidence.

## Trigger

The second CI failure of this test, or any failure of it on a developer
machine. Then: print the assertion message in CI (upload
`tools/fake-client/build/test-results`) and fix the race it shows.

## How it was paid

Same cause and fix as DEBT-0056, which records the evidence: `MainTest`
uses the same test desktop (`ackThenClose`) as `HandshakeFailureTest`.
