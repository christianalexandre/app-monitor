---
id: DEBT-0075
title: The desktop does not read grpc-status
status: Paid
date: 2026-10-25
trigger: "The PR of PLAN-0009 M8.7 (the desktop reads grpc-status)"
paid_date: 2026-10-25
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

The PR of PLAN-0009 M8.7 (the desktop reads grpc-status).

Fired by PLAN-0009 M8.4, which touches `HttpExchange.kt` to carry a
connection's bytes. Reading `grpc-status` spans the list, issues,
benchmarks and MCP, and M8.6 stores gRPC from the proxy with the same
header: paid once there. Narrowed to M8.6.

Fired by PLAN-0009 M8.6, which stores the proxy's gRPC calls with
`grpc-status` among the response headers (ADR-0106). Reading it across
the list, issues, benchmarks and MCP is its own task now that both
writers exist: M8.7, so M8.6's PR stays the proxy's.

## How it was paid

PLAN-0009 M8.7 (ADR-0107): ingest keeps the last `grpc-status` response
header in the `grpc_status` column (migration `2.sqm`). An exchange with one
other than 0 is an error to benchmarks, run diff, `endpoint_stats` and
`diff_runs`; the list and detail show `grpc N`; MCP rows carry
`grpc_status`. Tested with the adapter's headers and the proxy's real
grpc-java call (`GrpcStatusTest`, `GrpcStatusToolsTest`, `GrpcStatusUiTest`,
`Http2ProxyTest`).
