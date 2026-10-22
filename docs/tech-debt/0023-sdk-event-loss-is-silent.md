---
id: DEBT-0023
title: Events the SDK drops are invisible on the desktop
status: Open # Open | Paid | Dropped
date: 2026-10-22
trigger: A developer reports an event missing from the desktop, or the protocol gains per-envelope sequence numbers
paid_date:
---

# DEBT-0023 — Events the SDK drops are invisible on the desktop

## What is missing

`sdk/core` drops the oldest envelopes when its offline buffer is full, drops
an envelope larger than `Protocol.MAX_FRAME_BYTES`, and loses envelopes
written to a socket that then dies (ADR-0022, SDD 04). The desktop sees a
request without its response, or nothing, and cannot tell a lost event from
one never sent.

## Why it was accepted

Counting or numbering envelopes needs a protocol field and a desktop view
for it; no SDK sends events yet, so the loss rate is unknown.

## Trigger

A developer reports an event missing from the desktop, or the protocol
gains per-envelope sequence numbers.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
