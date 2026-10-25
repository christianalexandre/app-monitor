---
id: ADR-0107
title: The desktop stores grpc-status in its own column
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0107 — The desktop stores grpc-status in its own column

## Context

PLAN-0009 M8.7, DEBT-0075. A gRPC call the server accepted and ended with
a status other than OK reaches the desktop as `ResponseReceived` with
status 200 and its code in a `grpc-status` response header, from both
writers:

- the `AppMonitorGRPC` adapter appends `grpc-status` after the metadata and
  trailers (`sdk/ios/Sources/AppMonitorGRPC/GRPCCapture.swift`, ADR-0102);
- the proxy adds the trailers to the response's headers
  (`desktop/proxy/src/main/kotlin/appmonitor/desktop/proxy/Http2Relay.kt`,
  ADR-0106).

The list, benchmarks, run diff and MCP decide an error from `status_code`
and `failed` columns, which lists read without the headers (`response_meta`
stays unread there, SDD 09). `failed` means "no response" (a transport
error) to the window and to MCP's `failed_only`.

## Options

1. **A `grpc_status` column**, set at ingest from the last `grpc-status`
   header, read by every list query. One migration (`2.sqm`); readers
   test one more column; `failed` keeps its meaning. Rows stored before the
   migration keep it null.
2. **`failed = 1` and `error = "grpc-status N"` at ingest.** No schema
   change, every reader follows; but a call with a response then reads as
   one without (the window shows "failed" over the 200), and `failed_only`
   mixes transport and application errors.
3. **Decode `response_meta` in each reader.** No schema change; every list
   and tally reads and decodes a blob per row, which ADR-0005 keeps out of
   list queries.

## Decision

Option 1. `StoredExchange.isError` (failed, status 400 and up, or
`grpc_status` other than 0) is what benchmarks, run diff and MCP's
`endpoint_stats` and `diff_runs` count; the list shows `grpc N` in place of
the status; MCP rows carry `grpc_status`. The last header wins, since
trailers come after the headers. `failed_only` and the status class filter
are unchanged.

## Consequences

- Rows stored before schema version 3 are not backfilled: a failed gRPC
  call from those reads as 200, as it did.
- HAR and cURL keep the HTTP status; `grpc-status` is among the headers.
- A filter on gRPC errors alone is not offered; `search_requests` returns
  `grpc_status` for the agent to filter on.
