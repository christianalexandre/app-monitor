---
id: DEBT-0053
title: A connect cancelled in its TCP phase may leak its socket on iOS
status: Open # Open | Paid | Dropped
date: 2026-10-24
trigger: The `ktor` version in `libs.versions.toml` changes, or a KMP iOS app reports file descriptors or sockets growing across `AppMonitor.stop()` and `start()`
paid_date:
---

# DEBT-0053 — A connect cancelled in its TCP phase may leak its socket on iOS

## What is missing

Ktor CIO 3.6.0 wraps its TCP connect in `withTimeoutOrNull`; on
Kotlin/Native, a cancellation that lands as the connect returns drops the
socket without closing it (ADR-0067, 1 run in 8 of
`stopWhileConnectingClosesTheHalfOpenSocket` on the simulator before the
fix). `stop()` no longer cancels a connect (ADR-0067 C.1), but its 1 s
release bound still cancels one that is still in its TCP phase, e.g. a host
that drops SYNs. Symptom: one open socket left per such `stop()`, seen as a
growing descriptor count, never as a wrong capture.

## Why it was accepted

The leak needs a `stop()` during a TCP connect slower than 1 s, at most one
socket each time, in a debug build. The fix is in Ktor, not here; no Ktor
issue was searched or filed from the loop (an outward action).

## Trigger

The `ktor` version in `libs.versions.toml` changes (check whether
`Endpoint.connect` still drops the socket on cancellation), or a KMP iOS app
reports file descriptors or sockets growing across `AppMonitor.stop()` and
`start()`.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
