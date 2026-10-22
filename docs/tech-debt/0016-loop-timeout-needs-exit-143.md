---
id: DEBT-0016
title: A watchdog kill that does not exit 143 is not reported as a timeout
status: Open
date: 2026-10-22
trigger: A loop session killed by the watchdog is logged as "no verdict line" instead of a timeout
paid_date:
---

# DEBT-0016 — A watchdog kill that does not exit 143 is not reported as a timeout

## What is missing

`run_session` returns 124 only when the `.timeout` marker exists **and** the
session exited 143 (ADR-0015). A session that handles `TERM` and exits with
another code (1, 130) keeps that code, so the loop logs "no verdict line"
instead of a timeout, and the `.timeout` marker stays in `.loop/`.

## Why it was accepted

ADR-0015 chose exit 143 because the marker alone cannot tell a session that
exited at the deadline from one the watchdog killed. No real `claude` session
has been seen exiting with another code after `TERM`.

## Trigger

A loop session killed by the watchdog is logged as "no verdict line" instead
of a timeout.

## How it was paid

> Filled when closing.
