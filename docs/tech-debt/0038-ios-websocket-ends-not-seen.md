---
id: DEBT-0038
title: Some ends of an iOS WebSocket are not captured
status: Open
date: 2026-10-23
trigger: M2.13 shows an iOS WebSocket ending differently from the Android sample's, or a developer reports an iOS WebSocket missing its close or failure
paid_date:
---

# DEBT-0038 — Some ends of an iOS WebSocket are not captured

## What is missing

On the desktop, an iOS WebSocket can stop without a last event: a socket
that drops after the `101` sends no `RequestFailed` (the OkHttp capture
does), a close frame from the peer is recorded only if the app had a
`receive()` pending, and `cancel()` without a code records no close frame.

## Why it was accepted

ADR-0045's hooks are on the task's own methods, and none of them is called
in those cases (probed on macOS 26.4): `connection:didFinishLoadingWithError:`
fires only for an upgrade that failed, and the peer's close is visible only
in `closeCode` when a receive fails. Seeing them needs the framing layer
below the task (ADR-0045 option 3) or a delegate proxy, for an app whose
receive loop already shows the close in most cases.

## Trigger

M2.13 shows an iOS WebSocket ending differently from the Android sample's,
or a developer reports an iOS WebSocket missing its close or failure.

## How it was paid

> Filled when closing.
