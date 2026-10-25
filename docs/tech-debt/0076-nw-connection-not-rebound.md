---
id: DEBT-0076
title: The connection layer does not rebind nw_connection_*
status: Open
date: 2026-10-25
trigger: "The owner's Instruments finding (ADR-0100 D) names an SDK that calls Network.framework's `nw_connection_*` itself, or a developer's connection made through Network.framework missing from the list"
paid_date:
---

# DEBT-0076 — The connection layer does not rebind nw_connection_*

## What is missing

ADR-0100 A.1 lists `nw_connection_create`, `_start` and `_cancel` with
their state handler among the rebound symbols. M8.3 rebinds the BSD
socket calls only (SDD 07, "The connection layer"), so a connection an
app's own code opens with Network.framework's C API, or with Swift's
`NWConnection` from an image the rebinding reaches, is not reported.

## Why it was accepted

Reporting an `nw_connection_t` means wrapping the app's state handler
block, keeping the endpoint from `nw_connection_create`, and reading byte
counts from `nw_connection_send` / `_receive` completions: a second
mechanism as large as the BSD one. No SDK known to the project uses that
API directly; gRPC-Core, the case ADR-0004 names, uses BSD sockets. The
Instruments finding of ADR-0100 D says whether one does.

## Trigger

The owner's Instruments finding (ADR-0100 D) names an SDK that calls Network.framework's `nw_connection_*` itself, or a developer's connection made through Network.framework missing from the list.

Fired by the PLAN-0009 milestone review. Not paid: the owner's Instruments finding (ADR-0100 D) is still not in, and nothing in the plan calls `nw_connection_*`. Re-triggered on the events that show the gap.

## How it was paid

> Filled when closing.
