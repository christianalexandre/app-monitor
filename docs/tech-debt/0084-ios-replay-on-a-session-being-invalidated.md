---
id: DEBT-0084
title: An iOS replay can race the app invalidating its session
status: Open
date: 2026-10-25
trigger: The next PR that changes `sdk/ios/Sources/AppMonitor/Replays.swift`, or the first crash "Task created in a session that has been invalidated" with `Replay` in its stack
paid_date:
---

# DEBT-0084 — An iOS replay can race the app invalidating its session

## What is missing

`Replay` reads the session's `_invalid_ivar` and then makes the new task
(ADR-0124 C.1). An app that calls `finishTasksAndInvalidate()` or
`invalidateAndCancel()` on another thread between the two raises
`NSGenericException`, which ends the debug app. Symptom: that exception
right after the window's "Send again" or an MCP replay.

## Why it was accepted

The window opens microseconds wide, and only on a replay the developer
asked for, of a session the app is tearing down at that moment. Closing
it needs the two invalidation methods hooked and a lock shared with the
app's call, or an Objective-C shim that catches the exception
(ADR-0124 C.2).

## Trigger

The next PR that changes `sdk/ios/Sources/AppMonitor/Replays.swift`, or
the first crash "Task created in a session that has been invalidated"
with `Replay` in its stack.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
