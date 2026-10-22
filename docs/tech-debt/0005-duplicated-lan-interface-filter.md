---
id: DEBT-0005
title: LAN interface filter duplicated in server and fake client
status: Open
date: 2026-10-21
trigger: The first change to either filter, or M1 sdk/core implements discovery
paid_date:
---

# DEBT-0005 — LAN interface filter duplicated in server and fake client

## What is missing

`tools/fake-client` `Discovery.lanAddresses()` is a copy of
`MdnsAdvertiser.lanAddresses()` in `desktop/server`. Discovery only works when
both pick the same interfaces; a fix to one (excluding docker or `utun`, say)
silently drifts from the other.

## Why it was accepted

Found in the M0 milestone review. The fake client only depends on
`desktop/server` in tests; a shared JVM module for one 10-line function is
premature while `sdk/core` (M1) will need its own discovery and shows where the
shared code belongs.

## Trigger

The first change to either filter, or M1 `sdk/core` implements discovery —
then the filter moves to one shared place.

## How it was paid

> Filled when closing.
