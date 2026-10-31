---
id: DEBT-0023
title: Events the SDK drops are invisible in the desktop window
status: Paid # Open | Paid | Dropped
date: 2026-10-22
trigger: PLAN-0015 H5.3c, the next task after H5.3b
paid_date: 2026-10-31
---

# DEBT-0023 — Events the SDK drops are invisible in the desktop window

## What is missing

`sdk/core` drops the oldest envelopes when its offline buffer is full, drops
an envelope larger than `Protocol.MAX_FRAME_BYTES`, and loses envelopes
written to a socket that then dies (ADR-0022, SDD 04). Since H5.3b both
SDKs report these counts in a `DeliveryReport` and `AppMonitorServer`
turns them into `DeviceSession.drops` (ADR-0166); the headless server logs
them. The desktop app neither stores nor shows them: in its window a lost
event still looks like one never sent.

## Why it was accepted

Counting or numbering envelopes needs a protocol field and a desktop view
for it; no SDK sent events yet, so the loss rate was unknown. H5.3 was split:
the wire first (it is what M10 freezes), the window in its own task.

## Trigger

PLAN-0015 H5.3c, the next task after H5.3b: the store keeps each session's
`drops` and the devices pane shows them.

## How it was paid

PLAN-0015 H5.3b put the counts on the wire (ADR-0166); H5.3c stores each
session's latest in `sessions.drops_*` (migration 8) and the devices pane
names them once one is above zero, "lost unknown" when the server could
not count the loss (`DropsUiTest`, `drops.png`). `TrafficStore` reads
`DeviceSession.drops` with each envelope rather than collecting the
`StateFlow` apart: the server sets it before delivering the report, so the
store sees it in order with the session's envelopes and no second
coroutine races `disconnected`.

Left: the last session of an SDK start never reports its drops (ADR-0166),
and the MCP tools do not return them; no agent has asked for them.
