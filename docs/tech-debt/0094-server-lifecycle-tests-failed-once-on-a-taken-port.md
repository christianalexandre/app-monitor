---
id: DEBT-0094
title: Two server lifecycle tests failed once on a taken port
status: Paid # Open | Paid | Dropped
date: 2026-10-26
trigger: A test in `desktop/server/src/test/` fails again with `BindException`, locally or on CI
paid_date: 2026-10-31
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

## How it was paid

Reproduced on macOS by a scratch harness, not committed: a Python
process held every port of the ephemeral range (49152–65535) but 50, and
kept 40 of those 50 bound through port-0 binds, each released after about
120 ms. Under it, `:desktop:server:test` for `ServerLifecycleTest`,
`MdnsPartialFailureTest` and `MainTest` failed 3 runs of 3 (2, 6 and 11
tests), with `BindException` where a test bound again the port it had
picked with `ServerSocket(0)`. A harness that only held the range, without
taking ports, did not reproduce it.

Those tests now give the server port 0 and read the port it bound, from
`start()`, the advertiser factory's `boundPort` or the engine's resolved
connector, so nothing else can take it between the pick and the bind.
Under the same harness every changed test passed in 3 runs of 3; one run
failed two tests that already bound port 0, their client's `connect`
refused with `Can't assign requested address`: the harness had used up the
range, not the race fixed here. Without the harness: 50 runs in a row
green.
