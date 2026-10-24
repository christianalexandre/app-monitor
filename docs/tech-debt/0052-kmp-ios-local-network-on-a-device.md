---
id: DEBT-0052
title: The KMP SDK's BSD socket connection is unproven under local network privacy on a physical iPhone
status: Open # Open | Paid | Dropped
date: 2026-10-24
trigger: A physical iPhone is connected to the Mac that runs the loop, or a developer reports the KMP SDK cannot reach the desktop from an iPhone
paid_date:
---

# DEBT-0052 — The KMP SDK's BSD socket connection is unproven under local network privacy on a physical iPhone

## What is missing

`sdk/core` on iOS connects with Ktor CIO over BSD sockets (ADR-0066).
Local network privacy exists only on a device (TN3179, ADR-0036), and how
a blocked or pending connect looks to CIO there — an error, a hang until
the 10 s bound — has not been observed. Symptom: an iPhone running a KMP
debug build shows the prompt, the user allows it, and the device never
appears on the desktop, or only after a long backoff.

## Why it was accepted

No physical iPhone is attached to the loop's Mac (DEBT-0041); the
simulator has no prompt. SDD 04's backoff retries any connect failure, so
the expected outcome is a late connection, not none.

## Trigger

A physical iPhone is connected to the Mac that runs the loop, or a
developer reports the KMP SDK cannot reach the desktop from an iPhone.

## How it was paid

> Filled when closing.
