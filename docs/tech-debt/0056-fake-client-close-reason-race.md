---
id: DEBT-0056
title: The fake client can lose the desktop's close reason when the connection drops mid-send
status: Open
date: 2026-10-24
trigger: A second CI or local failure of `HandshakeFailureTest`, or the next PR that touches `Replay.kt`
paid_date:
---

# DEBT-0056 — The fake client can lose the desktop's close reason when the connection drops mid-send

## What is missing

`HandshakeFailureTest` "fails with the close reason when the desktop closes
after HelloAck" failed once on CI (run 40109874557, PR #156, ubuntu) with an
`AssertionFailedError` at `assertFailsWith<SessionClosedException>`, and
passed 5 of 5 local reruns. `replay` in `tools/fake-client/.../Replay.kt`
sends the fixture traffic while the peer closes with `PROTOCOL_ERROR`; when
the connection ends before the Close frame is read, `awaitClose` returns null
and `replay` throws `IOException("Connection to the desktop dropped")`
instead of `SessionClosedException` with the reason. Suspected cause, not
proven: the CI log has no exception type.

## Why it was accepted

One failure in the last 30 CI runs, in a dev tool whose only cost is a less
precise error line; PR #156 does not touch `tools/fake-client`.

## Trigger

A second failure of `HandshakeFailureTest` in CI or locally, or the next PR
that touches `Replay.kt`. Then log the exception type the test got, and
read the incoming Close frame before reporting the drop.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
