---
id: ADR-0103
title: How a rebound socket maps onto the network events
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0103 — How a rebound socket maps onto the network events

## Context

PLAN-0009 M8.3. ADR-0100 decides what the connection layer captures (host,
port, bytes each way, open and close times, the error of a failed connect)
and through which symbols, and leaves open how it travels: the protocol
has `RequestStarted`, `ResponseReceived` and `RequestFailed`
(`protocol/proto/appmonitor/v1/network.proto`), none of which carries
bytes sent apart from a request body known when the request starts.

Evidence:

- The desktop stores an event at its envelope's `timestamp_ms`
  (`desktop/storage/.../Ingest.kt`, `network`), so a sender can date an
  event to when it happened rather than when it was sent.
- Each event after `RequestStarted` links to it by `request_id`, and
  `ResponseReceived` updates the same exchange row (`queries.responded`).
- A gRPC-Core channel keeps its connection open for the life of the
  channel; a connection reported only at `close` stays invisible while
  the app runs.
- ADR-0102 A rejects status 0 for an RPC because every desktop reader
  takes it as "no response"; a connection has no response status, so
  here that reading is the right one (M8.4: "no status, no body").

## Options

1. **`RequestStarted` at `connect`, `ResponseReceived` at `close` with a
   new `optional int64 sent_size = 7`.** Method `CONNECT`, URL
   `tcp://host:port`, `capture_layer = CONNECTION`, dated to before the
   `connect` call; at `close`, status 0, no body, `body_size` the bytes
   read and `sent_size` the bytes written; a failed connect, or one that
   never completed by `close`, is `RequestFailed` with
   `NSPOSIXErrorDomain <errno>: <strerror>`. An open connection shows as
   pending. One field added, ignored by older readers (proto3).
2. **Both events at `close`**, `RequestStarted` dated to the `connect`
   with `body_size` the bytes written. No protocol change; a connection
   is unseen until it closes, which for a gRPC channel is never.
3. **A new `ConnectionClosed` message.** Exact names, but a new event
   type every desktop reader must learn before a closed connection stops
   looking pending.

## Decision

Option 1. `sent_size` is set on `CONNECTION` exchanges only. The URL host
is the name the app's last `getaddrinfo` gave that address, else the
numeric address (IPv6 in brackets).

## Consequences

- `request_started.bin` and the other fixtures are unchanged; M8.4 adds a
  `CONNECTION` fixture with the readers that use it.
- Until M8.4 the desktop shows a connection as a `CONNECT` row with
  status 0.
- A connection reset after bytes moved is `RequestFailed`, without its
  byte counts.
