---
id: DEBT-0078
title: No pass-through list for hosts the proxy should not intercept
status: Open
date: 2026-10-25
trigger: PLAN-0009's milestone review, or a developer's pinned host that keeps failing through the proxy, whichever comes first
paid_date:
---

# DEBT-0078 — No pass-through list for hosts the proxy should not intercept

## What is missing

ADR-0101 E.1 intercepts every host and adds "a pass through list in the
settings [that] tunnels those hosts untouched". M8.5 builds the first
half only: a client that pins its certificates shows as a failed
`CONNECT` exchange (SDD 11), and there is no way to let that host
through untouched while the proxy is on.

## Why it was accepted

The failure is already visible and the proxy is off by default; with
ADR-0101 B.1 only an app launched with `grpc_proxy` goes through it, so a
pinned host blocks only that launch. The list is a setting, a second
pipeline (raw tunnel) and a test of its own.

## Trigger

PLAN-0009's milestone review, or a developer's pinned host that keeps failing through the proxy, whichever comes first.

Fired by PLAN-0009 M8.6, which touches `ClientHandler.kt` only to hand
HTTP/2 tunnels to `Http2Relay.kt`; a pinned client still fails at the
TLS step, before HTTP/1.1 or HTTP/2 matters, so the list is the same
work as before. Re-triggered on the milestone review.

## How it was paid

> Filled when closing.
