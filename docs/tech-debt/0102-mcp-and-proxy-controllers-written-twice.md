---
id: DEBT-0102
title: The MCP and proxy controllers repeat one switch and lifecycle
status: Open
date: 2026-10-30
trigger: "A third switchable desktop service, or the next fix to the switch, lock or status of `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/McpController.kt` or `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/ProxyController.kt`"
paid_date:
---

# DEBT-0102 — The MCP and proxy controllers repeat one switch and lifecycle

## What is missing

`McpController` and `ProxyController` each write the same pattern: a
stored switch read at `start()`, a `switchLock` and `switched` flag so a
switch made during that read wins, a `Mutex` around start and stop,
`ServiceStatus` (`Off`, `Starting`, `Running`, `PortTaken`, `Failed`),
`close()` before the store closes. A fix to one does not reach the
other. Symptom: the two behave differently under the same sequence. An
external review found one: the "last switch wins" race was fixed in
`McpController` (DEBT-0073) but not in `ProxyController`, where #313
fixes it.

## Why it was accepted

There are two services, and they differ in what they start (an MCP
server with a token, a proxy with a CA and a recording) and in what
"Clear all data" does to them. A shared base for two cases would be
written against guesses. `McpWiringTest` and `ProxyWiringTest` race the
switch in both.

## Trigger

A third switchable desktop service, or the next fix to the switch, lock
or status of
`desktop/app/src/main/kotlin/appmonitor/desktop/app/store/McpController.kt`
or
`desktop/app/src/main/kotlin/appmonitor/desktop/app/store/ProxyController.kt`.
Then the switch, lock and status move into one class that each service
gives its own start and stop.

## How it was paid

> Filled when closing.
