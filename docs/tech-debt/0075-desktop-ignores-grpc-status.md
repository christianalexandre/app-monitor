---
id: DEBT-0075
title: The desktop does not read grpc-status
status: Open
date: 2026-10-25
trigger: The PR of PLAN-0009 M8.6 (gRPC stored through the proxy), or the first PR that touches `desktop/app/src/main/kotlin/appmonitor/desktop/app/store/HttpExchange.kt` after PLAN-0009 M8.2
paid_date:
---

# DEBT-0075 — The desktop does not read grpc-status

## What is missing

A gRPC call the server accepted and ended with a status other than OK
arrives as `ResponseReceived` with status 200, its code only in the
`grpc-status` header (ADR-0102). The list, issues, benchmarks and MCP
read `status_code`, so such a call shows as a success.

## Why it was accepted

M8.2 is the iOS adapter; reading `grpc-status` is desktop work that the
proxy's gRPC exchanges (M8.6) need too, from the same header. Doing it
once, when both writers exist, tests it with what both really send.

## Trigger

The PR of PLAN-0009 M8.6, which stores gRPC through the proxy, or the
first PR after M8.2 that touches
`desktop/app/src/main/kotlin/appmonitor/desktop/app/store/HttpExchange.kt`.

## How it was paid

> Filled when closing.
