---
id: DEBT-0039
title: Background tasks resumed before start() or in an earlier launch are not captured
status: Paid
date: 2026-10-23
trigger: M2.12's background download is missing from the desktop after the sample is relaunched, or a developer reports a background transfer missing
paid_date: 2026-10-25
---

# DEBT-0039 — Background tasks resumed before start() or in an earlier launch are not captured

## What is missing

A background transfer that the app resumed before `AppMonitor.start`, or in
a previous launch that iOS ended, finishes with no event on the desktop: the
app's delegate gets it, the monitor does not.

## Why it was accepted

ADR-0046 registers a task on its session at `resume`, where the request is
known and its id is made. A task from an earlier launch is never resumed in
this one; capturing it means sending `RequestStarted` at completion with a
start time that is not the real one, a choice worth making with M2.12's
sample in hand.

## Trigger

M2.12's background download is missing from the desktop after the sample is
relaunched, or a developer reports a background transfer missing.

## How it was paid

PLAN-0011 H1.4, ADR-0131. The first background callback for a task the
`resume` hook never registered, while the monitor runs, adopts it; at
completion `RequestStarted` is sent from the task's metrics, stamped with
the start `nsurlsessiond` recorded, then the response. Left: no test
relaunches the app, so an earlier launch's task is covered by the same
path only; an adopted upload's request body is not seen.
