---
id: DEBT-0116
title: The end to end target once refused the call after `/delay/10`
status: Open
date: 2026-10-31
trigger: A second `make e2e-kmp-android`, `make e2e-android` or `make e2e-ios` run where the call after `/delay/10` fails to connect to `tools/e2e-target`
paid_date:
---

# DEBT-0116 — The end to end target once refused the call after `/delay/10`

## What is missing

One `make e2e-kmp-android` run on the owner's Mac (2026-10-31, H5.4) failed:
the sample's `/delay/10` call timed out as expected, then its
`ws://10.0.2.2:<port>/ws` call got `java.net.ConnectException` from
`tools/e2e-target`, whose log ends at `GET /delay/10`. The script then
reported the `/delay/10` `RequestFailed` missing, though the server log
had it. The next run passed. The cause is not known: the target, the
emulator's `10.0.2.2` forward, or the script's log reading.

## Why it was accepted

Seen once, on a change (ADR-0168) whose end to ends use a manual host and
never reach the pin; the rerun passed on the same tree.

## Trigger

A second `make e2e-kmp-android`, `make e2e-android` or `make e2e-ios` run
where the call after `/delay/10` fails to connect to `tools/e2e-target`.
That run keeps `build/e2e-*/target.log` and the logcat, and checks whether
the target process is still alive.

## How it was paid

> Filled when closing.
