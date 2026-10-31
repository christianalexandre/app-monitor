---
id: DEBT-0056
title: The fake client can lose the desktop's close reason when the connection drops mid-send
status: Paid
date: 2026-10-24
trigger: A second CI or local failure of `HandshakeFailureTest`, or the next PR that touches `Replay.kt`
paid_date: 2026-10-30
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

Paid by PLAN-0015 H5.2a, with DEBT-0032; one cause for both:
the test desktop (`ackThenClose`) closed TCP right after its close frame,
with the client's traffic still unread, so its kernel reset the connection.
Ktor 3.6.0 sends a close without waiting for the peer's
(`DefaultWebSocketSession.sendCloseSequence`), and its reader cancels its
frame queue on a read `IOException` (`WebSocketReader`): on Linux the
client lost the close frame not yet handed over and reported the drop.

Evidence: a peer that sends HelloAck and a `PROTOCOL_ERROR` close, then
resets once the client sends, made `replay` report "Connection to the
desktop dropped" in 179 of 200 runs in a Linux container (2 CPUs), 0 of
200 on macOS, and 0 of 200 when the peer waited 100 ms before the reset.
Reading the close on the client while it sends did not help (181 of 200):
the frame never reaches `incoming`. The CI failure itself did not show in
5,000 Linux and 3,400 macOS runs of the old test desktop.

`ackThenClose` now reads until the client's close, bounded at 1 s, as RFC
6455 section 7.1.1 asks of the side that closes. 2,000 runs on Linux (1
CPU) and 50 `--rerun`s of `HandshakeFailureTest` and `MainTest` on macOS
green. The desktop closes the same way: DEBT-0066 carries that.
