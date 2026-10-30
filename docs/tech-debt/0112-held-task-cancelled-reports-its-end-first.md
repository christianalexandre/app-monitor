---
id: DEBT-0112
title: A held iOS task the app cancels can report its end before its start
status: Open
date: 2026-10-30
trigger: A second failure of `aHeldTaskTheAppCancelsIsCapturedStartedThenFailed` in `sdk/ios/Tests/AppMonitorTests/BreakpointTests.swift`, or PLAN-0015 H5.2 starts
paid_date:
---

# DEBT-0112 — A held iOS task the app cancels can report its end before its start

## What is missing

A request breakpoint holds `RequestStarted` until the hold ends (ADR-0123 C.1).
When the app cancels the held task, `HoldProtocol.stopLoading` sends it on the
loading thread. The connection hooks send `RequestFailed` when the task
completes, which can come first. In release gate run 41131356928 (`contract`,
macOS, PR #323 at its first push) the test got
`["RequestFailed", "RequestStarted"]` instead of
`["RequestStarted", "RequestFailed"]`. On the owner's Mac it passed 30 of 30
times alone and in 15 of 15 runs of the whole suite. #321 changed only where
the task is read from, not this order.

Symptom: the desktop gets the end of an exchange the app cancelled during a
request breakpoint before the request it ends.

## Why it was accepted

One CI failure, not reproduced locally. PLAN-0015 H5.2 fixes each CI-only
failure at its cause.

## Trigger

A second failure of that test, or PLAN-0015 H5.2 starts.

## How it was paid

> Filled when closing.
