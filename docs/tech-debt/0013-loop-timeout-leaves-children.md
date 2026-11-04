---
id: DEBT-0013
title: Loop timeout kills only the session process
status: Paid
date: 2026-10-22
trigger: An iteration after a timeout fails on a held port or a Gradle lock
paid_date: 2026-11-04
---

# DEBT-0013 — Loop timeout kills only the session process

## What is missing

On `ITERATION_TIMEOUT`, `scripts/loop.sh`'s watchdog sends `TERM` to the
`claude` pid only, not to its process group. Children it started (a test
JVM, `make desktop`) can keep port 47474 or worktree files busy for the next
iteration.

## Why it was accepted

Found in the fifth M0 review. No timeout has fired in a real loop run yet.
Gradle work runs in the daemon, which is detached from the group anyway, so a
group kill would not free its locks; the right cleanup is not known until a
real case shows what is left behind.

## Trigger

An iteration that starts after a timeout fails on a held port or a Gradle
lock.

## How it was paid

PLAN-0016 H6.6a, ADR-0196: the watchdog sends `TERM` to the session and every process
under it, and `KILL` after `KILL_GRACE`. A process group, as the title of the task
asked, would not have done it: a real session's Bash tool runs in its own group. The
trigger never fired. `test_loop.sh` leaves no child that ignores `TERM` or sits in its
own group. A daemon that has already detached survives, as this item expected.
