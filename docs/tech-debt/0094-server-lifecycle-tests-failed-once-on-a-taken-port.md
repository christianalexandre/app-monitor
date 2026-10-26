---
id: DEBT-0094
title: Two server lifecycle tests failed once on a taken port
status: Open # Open | Paid | Dropped
date: 2026-10-26
trigger: A test in `desktop/server/src/test/` fails again with `BindException`, locally or on CI
paid_date:
---

# DEBT-0094 — Two server lifecycle tests failed once on a taken port

## What is missing

On 2026-10-26, during `make check` on `feat/neutral-color-palette`,
`ServerLifecycleTest`'s "stop while the engine is starting waits for start
and frees the port" and "second stop during a slow first release waits for
it" failed with `java.net.BindException: Address already in use`. Nothing
listened on 47474–47476 afterwards; `:desktop:server:test --rerun-tasks`
and a second `make check` passed.

The likely cause: the tests pick a port with `ServerSocket(0)`, close it,
then bind it again, and another test JVM of the same build can take it in
between. Not confirmed.

## Why it was accepted

Seen once, unrelated to the change being made (desktop colors), and both
reruns were green.

## Trigger

A test in `desktop/server/src/test/` fails again with `BindException`,
locally or on CI.
