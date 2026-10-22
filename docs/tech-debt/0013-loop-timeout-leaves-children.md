---
id: DEBT-0013
title: Loop timeout kills only the session process
status: Open # Open | Paid | Dropped
date: 2026-10-22
trigger: An iteration after a timeout fails on a held port or a Gradle lock
paid_date:
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

> Filled when closing. What the solution taught, and what is left of it.
