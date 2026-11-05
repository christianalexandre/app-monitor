---
id: DEBT-0066
title: The oversized frame server test hit EOF once on CI
status: Paid
date: 2026-10-24
trigger: "`ServerHardeningTest`'s oversized frame test (`desktop/server/src/test/kotlin/appmonitor/desktop/server/ServerHardeningTest.kt`) fails on CI a second time"
paid_date: 2026-10-31
---

# DEBT-0066 — The oversized frame server test hit EOF once on CI

## What is missing

`an oversized frame closes the connection with 1009` failed with
`java.io.EOFException` at `ServerHardeningTest.kt:250` (`readFrame`) in the
`check` job of PR #188, run 40142916262, on ubuntu. The bare socket reached
the end of the stream before it read a close frame. That PR changes only
`desktop/storage`, and the test passed in `make check` on the owner's
MacBook Pro. The cause is not known: the server may close the TCP
connection before its close frame is flushed, or the frame may be lost
when the socket is reset while the client's unread bytes are pending.

A likely cause, found by PLAN-0015 H5.2a (DEBT-0056): Ktor 3.6.0 sends
the desktop's close without waiting for the client's
(`DefaultWebSocketSession.sendCloseSequence`), and the handler returns, so
TCP closes with the client's bytes unread and the kernel resets the
connection. A peer resetting right after its close lost that close in 179
of 200 Linux runs. Here the unread bytes are the rest of the oversized
frame. A real SDK would see 1006, not 1009. The fix this suggests: the
desktop reads until the client's close, bounded, before it closes TCP.

## Why it was accepted

One failure in the CI history, on a change that does not touch
`desktop/server`. One sample cannot show whether the server drops its close
frame (a real bug: the SDK would see 1006 instead of 1009) or only the test
races.

## Trigger

`ServerHardeningTest`'s oversized frame test
(`desktop/server/src/test/kotlin/appmonitor/desktop/server/ServerHardeningTest.kt`)
fails on CI a second time. Then capture the bytes the socket read before
EOF and check whether Ktor writes the close frame before closing.

## How it was paid

Paid by PLAN-0015 H5.2d (`fix/server-close-before-hanging-up`).
Both causes were in the handler. On an oversized frame Ktor queues its
1009 and the handler returned at once, so the close could be lost; the
handler now flushes it, bounded at 1 s, before it returns. After each
close the desktop sends itself (1002, 1008, 1001) it now reads until the
device's close, bounded at 1 s, instead of closing TCP with the device's
frames unread.

Evidence: the oversized frame test's steps as a loop in an
`eclipse-temurin:21-jdk` container (2 CPUs), old server against new:
the old one hit EOF in 1 of 700 runs (1 of 200, then 0 of 500), the new
one in 0 of 1000, every other run read 1009. The rate is too low for that
alone to prove the fix; the new `ServerHardeningTest` case `after its
protocol error close the server reads until the client's close` fails on
the old server and passes on the new.
