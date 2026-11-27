---
id: DEBT-0141
title: Findings of the M11 milestone review that normal use does not hit
status: Open
date: 2026-11-27
trigger: The next PR that touches `UpdateChecker.kt` or `Main.kt`
paid_date:
---

# DEBT-0141 — Findings of the M11 milestone review that normal use does not hit

## What is missing

Two findings of PLAN-0021's review (its *M11 milestone review*), each read in the code:

1. `desktop/data/src/main/kotlin/appmonitor/desktop/data/UpdateChecker.kt`: `checking`
   starts `true`, and the stored switch, read after start, overwrites a `setChecking` made
   meanwhile. A store read that throws ends the loop for the session. Symptom: the Settings
   switch shows on for a moment although stored off, or no update is found in that session.
2. `desktop/app/src/main/kotlin/appmonitor/desktop/app/Main.kt`: "Clear all data" re-stores
   the switch through `setChecking`, which also wakes a check: one GitHub request per clear,
   besides the launch and the daily one.

## Why it was accepted

Neither is hit in normal use: (1) needs a toggle in the first moments after launch, or a
failing store; (2) costs one request per clear, under GitHub's limit.

## Trigger

The next PR that touches `UpdateChecker.kt` or `Main.kt`: that PR fixes the item of its
file.

## How it was paid

> Filled when closing.
