---
id: DEBT-0058
title: Non-correctness findings of the first M4 milestone review
status: Open
date: 2026-10-24
trigger: The first desktop or MCP feature that groups or filters exchanges by URL
paid_date:
---

# DEBT-0058 — Non-correctness findings of the first M4 milestone review

## What is missing

Findings of the `code-review` of `c93bd2a..ad3e5a7` that are not wrong
results, crashes, hangs, leaks, races or privacy issues against what the
SDD states today (ADR-0016):

- `sdk/core/src/commonMain/kotlin/appmonitor/sdk/core/Discovery.kt`: the
  KDoc of the internal `discovery` runs to 5 lines, delimiters included;
  so does the block at line 31 of
  `sdk/ktor/src/commonTest/kotlin/appmonitor/sdk/ktor/WebSocketCaptureTest.kt`
  (guidelines 02 allows 4).
- `sdk/ktor/src/commonMain/kotlin/appmonitor/sdk/ktor/Capture.kt`: a
  WebSocket upgrade is reported with its `ws://` or `wss://` URL, Ktor's own
  (SDD 02), while the Swift SDK reports `http(s)://`, as Foundation rewrites
  it, and OkHttp reports `http(s)://` too. The same endpoint shows under two
  schemes depending on the SDK, which splits URL queries and grouping in the
  desktop and the MCP server.

## Why it was accepted

The comment length is style. The scheme is what each HTTP library reports
and is documented as such; picking one scheme for every SDK is a protocol
choice best made with the desktop's grouping (no such feature yet), not in
a review round. M4.R1 compares URLs scheme-blind so the end to end's
duplicate check does not depend on it.

## Trigger

The first desktop or MCP feature that groups or filters exchanges by URL.

Re-triggered by `fix/ktor-body-read-failure`, which touched `Capture.kt`
and paid the two comment blocks: the scheme is a protocol choice that
change did not need, and only the grouping feature can pick it.

## How it was paid

> Filled when closing.
