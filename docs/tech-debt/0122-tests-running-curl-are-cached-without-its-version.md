---
id: DEBT-0122
title: Tests that run the system curl are cached without its version
status: Open # Open | Paid | Dropped
date: 2026-11-03
trigger: A CI runner image changes the outcome of a test that a `FROM-CACHE` result hid again, or the next PR that changes `desktop/proxy/build.gradle.kts`
paid_date:
---

# DEBT-0122 — Tests that run the system curl are cached without its version

## What is missing

`:desktop:proxy:test` runs the machine's `curl`, whose version is not one of the
task's inputs. On CI the task came `FROM-CACHE` in all of the last 40 runs but
the two that failed (2026-11-03): the runner's curl failed the test, for how long
is not known, and no one saw it until a PR changed what the task's classpath
holds, one with no proxy code. `ToCurlTest` in `desktop/mcp` runs `curl` the same way.

## Why it was accepted

The failing test is fixed, and the cache is why PRs stay fast (ADR-0012).
Reading `curl --version` as a task input is small, but it is a build change to a
fix that does not need it; it waits for a second miss.

## Trigger

A CI runner image changes the outcome of a test that a `FROM-CACHE` result hid
again, or the next PR that changes `desktop/proxy/build.gradle.kts`.
