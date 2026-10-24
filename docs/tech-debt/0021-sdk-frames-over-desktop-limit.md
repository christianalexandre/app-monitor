---
id: DEBT-0021
title: An SDK frame over the desktop's limit ends the session
status: Paid
date: 2026-10-22
trigger: M1.7 exposes `maxBodyBytes` in `sdk/android-okhttp`
paid_date: 2026-10-22
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

`AppMonitorOptions` rejects `maxBodyBytes` above `Protocol.MAX_BODY_BYTES`
(ADR-0025), so a captured body always fits a frame. What is left: a request
whose URL and headers exceed 256 KiB still makes an oversized envelope,
which `MonitorClient.send` drops before the buffer; the session survives,
the event is lost silently (DEBT-0023).
