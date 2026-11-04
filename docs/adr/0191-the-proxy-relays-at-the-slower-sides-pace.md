---
id: ADR-0191
title: The proxy relays at the slower side's pace and cuts an exchange it dropped an event of
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-04
superseded_by:
---

# ADR-0191 — The proxy relays at the slower side's pace and cuts an exchange it dropped an event of

## Context

Three gaps of the proxy (SDD 11), kept as DEBT-0081 and DEBT-0107:

- `ClientHandler` and `Http2Session` wrote each part to the other side
  with `writeAndFlush` and never looked at `isWritable`. A client reading
  slower than its origin writes leaves the difference in the proxy's
  memory: an origin streaming 256 MiB to a client that reads nothing wrote
  all of it, over HTTP/1.1 and over HTTP/2 (`ProxyFlowTest`, before this
  change).
- An HTTP/1.1 origin answering before the request's end (a refused
  upload) left the rest of the body unread by the proxy while the upstream
  connection stayed open for reuse: the next request on it went to an
  origin still reading the old body, and got `502` (`ProxyFlowTest`).
- `ProxyRecording` dropped any event past its 64 MiB bound. A dropped
  `RequestStarted` let a later event create an exchange with no method or
  URL; a dropped last event left it pending for good.

Netty reads a channel only while `autoRead` is on; a channel's
`isWritable` turns false past its write buffer's high mark (64 KiB by
default) and `channelWritabilityChanged` fires when it turns back. An
HTTP/2 child channel's writability follows the stream's flow control
window, and a child that is not read sends no `WINDOW_UPDATE`, so its
peer stops in turn.

## Options

1. **Pause the reading side while the writing side is full** — `autoRead`
   off on one side when the other is not writable, back on at
   `channelWritabilityChanged`; for HTTP/2 per stream, so one slow stream
   does not hold the others. No buffer of our own.
2. **A bounded buffer per exchange, the connection closed past it** —
   memory bounded, but a slow client loses its response.

For the store's bound:

1. **Cut the exchange** — the `Capture` learns from `record` that an event
   was dropped; a dropped `RequestStarted` sends nothing more, any other
   dropped event sends a `RequestFailed` saying the proxy dropped part of
   the exchange, and nothing after it. `RequestFailed` passes the bound:
   it is a few hundred bytes and at most one per exchange.
2. **Drop by request id in `ProxyRecording`** — needs each payload decoded
   to read its id, a 1 MiB body included.

## Decision

Option 1 for both: each side is read only while the other takes its
writes, and an exchange the recording dropped an event of is cut whole or
ended as failed with `Capture.CUT`. An HTTP/1.1 response that ends before
its request reads and drops the rest of the request's body and closes the
upstream connection instead of reusing it.

## Consequences

- The proxy's memory per exchange is bounded by the write buffers' marks
  and HTTP/2's windows, whatever the two peers' speeds.
- An exchange cut by the recording shows as failed with its reason, not
  as half an exchange or one pending forever.
- `ProxyServer`'s `record` answers whether it kept the event.
- `ProxyFlowTest` takes about 4 s: two transfers paused 1.5 s and 2 s.
