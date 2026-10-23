---
id: DEBT-0027
title: `stopWhileConnectingClosesTheHalfOpenSocket` fails intermittently
status: Open
date: 2026-10-22
trigger: CI fails on `MonitorClientTest`, or the next PR that touches `MonitorClient` or its stop path
paid_date:
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

> Filled when closing.
