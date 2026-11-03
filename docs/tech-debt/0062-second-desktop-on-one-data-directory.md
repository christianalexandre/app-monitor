---
id: DEBT-0062
title: A second desktop on the same data directory is not detected
status: Paid
date: 2026-10-24
trigger: The desktop's port becomes configurable in the app, or two desktops are seen writing the same `appmonitor.db`
paid_date: 2026-11-03
---

# DEBT-0062 — A second desktop on the same data directory is not detected

## What is missing

Two desktop apps started with the same data directory both open
`appmonitor.db` (SDD 09). Each opens a run at launch and runs retention, so
the second one's runs and deletions mix into the first one's history.

## Why it was accepted

The second instance cannot bind port 47474 while the first holds it, so
its server fails and no device traffic reaches its store; SQLite's locking
keeps the file consistent. A lock file (`FileChannel.tryLock`) is cheap,
but the case it covers needs a second port first.

## Trigger

The desktop's port becomes configurable in the app (`desktop/app`), or two
desktops are seen writing the same `appmonitor.db`.

## How it was paid

Paid by PLAN-0016 H6.1 (ADR-0175). `appmonitor.lock` held by `Storage.open`, a
second process refused before touching anything, and a window without "Clear all
data", which would have deleted the running desktop's files.
