---
id: DEBT-0027
title: `stopWhileConnectingClosesTheHalfOpenSocket` fails intermittently
status: Paid
date: 2026-10-22
trigger: CI fails on `MonitorClientTest`, or the next PR that touches `MonitorClient` or its stop path
paid_date: 2026-10-24
---

# DEBT-0027 — `stopWhileConnectingClosesTheHalfOpenSocket` fails intermittently

## What is missing

`MonitorClientTest.stopWhileConnectingClosesTheHalfOpenSocket` failed in 1 of
6 isolated runs on untouched `origin/dev` (`0b6ad6d`) and in 2 of 6 on the
M1.8 branch, with "the client left the socket open": the accepted socket saw
no end of stream within the test's 2 s read timeout after `stopAndJoin()`.
Unknown whether the CIO engine closes the half-open socket later than 2 s
(`HttpClient.close()` does not wait for its engine) or never (a leak per
cancelled connect).

## Why it was accepted

Found while running `make check` for M1.8 (`sdk/no-op`), which does not touch
`sdk/core` code; diagnosing the Ktor CIO close path is a task of its own.

## Trigger

CI fails on `MonitorClientTest`, or the next PR that touches `MonitorClient`
or its stop path — whichever comes first.

## How it was paid

In `feat/sdk-core-ios-targets` (ADR-0067). The socket was never closed, not
closed late: on the iOS simulator the test failed 1 run in 8, the desktop's
side read 0 bytes and no end of stream, while a bare ktor-network socket
closed by its owner ends at once. CIO's `Endpoint.connect` wraps the TCP
connect in `withTimeoutOrNull`, and a cancellation landing as it returns
drops the socket. `stop()` no longer cancels a connect; it lets it end
itself within the 1 s release bound. After the change: 16 of 16 full
`MonitorClientTest` runs green on the simulator, 12 of 12 on the JVM. What
is left, a connect still in its TCP phase at the bound, is DEBT-0053.
