---
id: DEBT-0021
title: An SDK frame over the desktop's limit ends the session
status: Open # Open | Paid | Dropped
date: 2026-10-22
trigger: M1.7 exposes `maxBodyBytes` in `sdk/android-okhttp`
paid_date:
---

# DEBT-0021 — An SDK frame over the desktop's limit ends the session

## What is missing

The desktop reads frames of at most `Protocol.MAX_FRAME_BYTES` (one
`MAX_BODY_BYTES` body plus 256 KiB, ADR-0021). `maxBodyBytes` (ADR-0018) has
no upper bound, and nothing bounds a request's URL and headers on the
device, so such an event closes the session with 1009 and the SDK reconnects.

## Why it was accepted

No SDK sends events yet. Whether the SDK caps `maxBodyBytes` at
`MAX_BODY_BYTES` and cuts long headers, or the desktop tells the device its
limit in `HelloAck`, is decided with the code that fills the event.

## Trigger

M1.7 exposes `maxBodyBytes` in `sdk/android-okhttp`.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
