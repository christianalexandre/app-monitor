---
id: ADR-0102
title: How a grpc-swift call maps onto the network events
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0102 — How a grpc-swift call maps onto the network events

## Context

PLAN-0009 M8.2. ADR-0099 C.1 makes one RPC one `ADAPTER` exchange, with
"`grpc-status` and `grpc-message` as status and trailers", and leaves
open what `status_code` holds, when a call fails, and how a single request
message gets into `RequestStarted.body`. The protocol has no trailer or
gRPC status field (`protocol/proto/appmonitor/v1/network.proto`); adding
one is a contract change for both SDKs and the desktop.

Evidence, from `grpc/grpc-swift-2` 2.4.4:

- `ClientStreamExecutor` turns a non-OK status into an `RPCError` thrown
  from the response's `bodyParts`, and an OK one into a
  `.trailingMetadata` part; a call the server never accepts (trailers
  only, or the transport failing) comes back as `accepted = .failure`.
- `StreamingClientRequest.producer` is called by the transport inside
  `next`, after the interceptor returns control; `GRPCClient.unary` and
  `serverStreaming` build it with `StreamingClientRequest(single:)`, a
  producer that writes one message.
- On the wire gRPC answers HTTP status 200 and carries its status in the
  trailers (gRPC over HTTP/2 spec), which is what the proxy of ADR-0101
  will see.

## Options

### A. `status_code` and failures

1. **200 and `grpc-status` in the headers** for every call that ended with
   a status after the server accepted it; `RequestFailed` with
   `grpc-status N (name): message` for a call it never accepted. Matches
   the wire, and what the proxy layer will store; a status other than OK
   on an accepted call shows as 200 until the desktop reads `grpc-status`.
2. **The gRPC code in `status_code`.** 0 for OK reads as "no response" to
   every desktop reader (filters, benchmarks, HAR) and differs from the
   proxy's record of the same call.
3. **A new `grpc_status` field.** Exact, at the cost of a protocol change
   for a value the headers already carry.

Recommended: 1.

### B. A single request message in `RequestStarted`

1. **Run a non-streaming producer once before the call** into a collector
   and replay the messages to the transport; `RequestStarted` carries the
   body like every other exchange. Safe because such a producer writes one
   message and does not wait on the transport.
2. **Delay `RequestStarted` until the first write.** Keeps the producer
   untouched; a call failing before the write needs a start sent from the
   error path, and the order of events depends on the transport.

Recommended: 1.

## Decision

A.1 and B.1, as SDD 07 tables them: status 200 with `grpc-status` and
`grpc-message` after the metadata and trailers, `RequestFailed` for a call
never accepted, the producer of a non-streaming request run once and
replayed.

## Consequences

- The desktop shows an accepted call that failed as 200 until M8.4 or
  M8.6 read `grpc-status`; SDD 07 lists it under limits.
- A non-streaming producer that throws throws again on replay, so the app
  sees the same error at the same point of the call.
