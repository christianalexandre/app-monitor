---
id: DEBT-0129
title: The gate's Windows job saves no Gradle cache
status: Open
date: 2026-11-05
trigger: "A Windows job of `release-gate.yml` times out, or the gate's first step takes over 10 minutes because of `desktop (windows-latest)`"
paid_date:
---

# DEBT-0129 — The gate's Windows job saves no Gradle cache

## What is missing

On `dev`, where `setup-gradle` writes, the `desktop (windows-latest)` job logs
`Basic caching failed to save entry with key setup-java-Windows-x64-gradle-…` and
`Failed to save: "C:\Program Files\Git\usr\bin\tar.exe" failed with exit code 2`
(runs `42074065010` and `42088402191`, 2026-11-05). Every Windows job starts cold.

## Why it was accepted

The job takes 7.8 to 8.6 minutes cold. On the warm run it was the first step's
longest job, against 6.5 for `contract`, so a cache would save the gate about
1.3 minutes at most. The cause was not looked into.

## Trigger

A Windows job of `release-gate.yml` times out, or the gate's first step takes over
10 minutes because of `desktop (windows-latest)`.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
