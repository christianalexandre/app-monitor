---
id: DEBT-0014
title: A rejection of a late frame reaches the client as a normal close
status: Open
date: 2026-10-22
trigger: When an SDK or a test needs to know the desktop rejected one of the last frames it sent before closing
paid_date:
---

# DEBT-0014 — A rejection of a late frame reaches the client as a normal close

## What is missing

Ktor's `DefaultWebSocketSession` answers a peer's close as soon as its
incoming processor reads it (`DefaultWebSocketSession.kt`, `is Frame.Close`
branch, Ktor 3.6.0), while the desktop's handler may still hold earlier frames
in its buffer. If one of them is rejected, the desktop's `PROTOCOL_ERROR` close
is dropped and the client sees `NORMAL`. Symptom: `make fake-traffic` exits 0
although the desktop logged a rejected frame near the end of the session.

## Why it was accepted

The fake client only sends well-formed fixtures, and the desktop rejects only
non-binary frames, so the case does not happen today. A rejection while the
client is still sending is reported (M0.R18). Fixing it needs an application
level close handshake or a session without Ktor's automatic close echo.

## Trigger

When an SDK or a test needs to know the desktop rejected one of the last frames
it sent before closing.

## How it was paid

> Filled when closing.
