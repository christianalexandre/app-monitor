---
id: DEBT-0034
title: Non-correctness findings of the third M1 milestone review
status: Open
date: 2026-10-23
trigger: The next PR that touches one of the files listed below
paid_date:
---

# DEBT-0034 — Non-correctness findings of the third M1 milestone review

## What is missing

Findings of the `code-review` of `f90e316..c7da3cb` that are not wrong
results, crashes, hangs, leaks, races or privacy issues (ADR-0016):

- `sdk/android-okhttp/.../Capture.kt`: the KDoc of `MASK` lists a masked
  header, query parameter or URL password, not the URL user name ADR-0032
  added.
- `docs/tech-debt/0033-m1-review-round-2-cleanups.md`: the percent-encoded
  mask item names the password and query parameter; the masked user name is
  encoded the same way.
- `sdk/android-okhttp/.../AppMonitorInterceptorTest.kt`: user info masking
  is tested through the interceptor only; neither a WebSocket URL through
  `AppMonitorWebSocketFactory` nor an empty user name with a password
  (`https://:pw@host/`) is tested.

## Why it was accepted

None of them changes what reaches the desktop today; each is a contained
change best made beside the next edit of its file.

## Trigger

The next PR that touches `Capture.kt`, `AppMonitorInterceptorTest.kt` or
DEBT-0033 pays that file's items.

## How it was paid

> Filled when closing.
