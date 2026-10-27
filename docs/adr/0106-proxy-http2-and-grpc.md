---
id: ADR-0106
title: How the desktop proxy relays HTTP/2 and stores gRPC
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0106 — How the desktop proxy relays HTTP/2 and stores gRPC

## Context

PLAN-0009 M8.6. ADR-0004 layer 4 promises "HTTP/2 + gRPC decoding";
ADR-0101 B.1 sends gRPC-Core traffic through the proxy with `CONNECT`,
and gRPC runs on HTTP/2 only. After M8.5 the proxy speaks HTTP/1.1 only
(SDD 11), so a gRPC client's tunnel fails. Already decided, not
reopened here: the proxy's binding, feed, CA and failures (ADR-0101),
Netty and Bouncy Castle (ADR-0105), the shape of an SDK's gRPC exchange
(ADR-0102: status 200, `grpc-status` among the response headers, a
single message as the body, streamed ones as binary `WebSocketFrame`s).

Evidence:

- `io.netty:netty-codec-http2:4.2.19.Final` is on Maven Central (the
  POM answers 200), same release and Apache-2.0 licence as the Netty
  modules ADR-0105 took. It has `Http2FrameCodec`,
  `Http2MultiplexHandler` (a child channel per stream) and
  `Http2StreamChannelBootstrap` (opens an outbound stream).
- gRPC on HTTP/2 (`grpc/grpc`, `doc/PROTOCOL-HTTP2.md`): a request is
  `content-type: application/grpc…`; each message is a compressed flag
  byte, a 4-byte big-endian length, then the bytes; the status comes in
  trailers, or in the only headers frame ("Trailers-Only").
- grpc-java behind an HTTP `CONNECT` proxy sends the HTTP/2 preface
  (`PRI * HTTP/2.0…`) straight into the tunnel on a plaintext channel
  (`Http2ProxyTest`), and curl `--http2` offers ALPN `h2` inside a TLS
  tunnel (same test class). gRPC-Core from a simulator is checked by
  M8.8's end to end, not here.
- grpc-java `1.85.1` is the latest release on Maven Central
  (`maven-metadata.xml`), Apache 2.0 in its POM; `grpc-netty-shaded`
  carries its own Netty, so it does not meet the proxy's 4.2.

## Options

### A. How HTTP/2 is relayed

1. **Frame by frame, per stream**: the client side gets an
   `Http2FrameCodec` and a child channel per stream; each stream opens
   a twin on one upstream HTTP/2 connection per tunnel; headers, data,
   trailers and resets cross as they come. Streaming RPCs work, nothing
   waits for a whole body.
2. **Translated to HTTP/1.1 objects** (`Http2StreamFrameToHttpObjectCodec`)
   and fed to M8.5's relay: one code path, but M8.5's relay is one
   exchange at a time per connection, and HTTP/2 multiplexes streams;
   a bidirectional RPC would block every other call on its connection.

Recommended: 1.

### B. How the proxy knows a tunnel is HTTP/2

1. **The preface**: the leaf's ALPN offers `h2`, then `http/1.1`; a
   decoder after the TLS step reads the first 24 bytes, and the HTTP/2
   preface switches the pipeline. The same check covers TLS (ALPN `h2`)
   and plaintext tunnels (prior knowledge).
2. **ALPN only**: no plaintext HTTP/2, so an insecure gRPC channel's
   tunnel fails.

Recommended: 1.

### C. The upstream side

1. **HTTP/2 to the origin**, ALPN offering `h2` only over TLS, prior
   knowledge in a plaintext tunnel. An origin without HTTP/2 makes each
   stream a `502` and a failed exchange, `<host>:<port> does not offer
   HTTP/2` (DEBT-0079).
2. **Translate to HTTP/1.1 when the origin has no HTTP/2**: covers
   HTTP/1.1-only HTTPS origins reached by an HTTP/2 client, at the cost
   of a third relay path; gRPC needs HTTP/2 end to end anyway.

Recommended: 1.

### D. How a gRPC call is stored

1. **Every message as a binary `WebSocketFrame`**, sent or received, no
   body; `RequestStarted` when the request headers arrive, the response
   headers and trailers as the response's headers. The proxy cannot
   tell a unary call from a streaming one on the wire, so it stores what
   the SDK stores for a call whose descriptor has no type
   (bidirectional, SDD 07).
2. **A single message as the body, more as frames**: matches ADR-0102
   for unary calls, but a client-streaming call that sent one message
   would look unary, and the choice is known only at the end.

Recommended: 1.

### E. Tests

1. **grpc-java `1.85.1` (`grpc-netty-shaded`, `grpc-stub`) in
   `desktop/proxy`'s tests only**, with byte array marshallers (no
   code generation): a real gRPC client and server through the proxy.
2. **Hand-written gRPC frames on a Netty origin**: no dependency, but
   it tests the proxy against our own reading of the protocol (M7's
   lesson).

Recommended: 1.

## Decision

A.1, B.1, C.1, D.1 and E.1: `netty-codec-http2` `4.2.19.Final` in
`desktop/proxy`, grpc-java `1.85.1` in its tests only. Request headers are
stored without the pseudo-headers (`:method`, `:path`, `:status` are the
method, URL and status already), trailers after the response headers,
and gRPC messages as sent: a compressed one keeps its compression.

## Consequences

- A gRPC call through the proxy and the same call through the
  `AppMonitorGRPC` adapter differ for unary calls: frames against a body.
  The layer caption tells them apart (ADR-0101 F.1).
- An HTTP/2 client (curl by default over TLS) reaching an HTTP/1.1-only
  HTTPS origin through the proxy gets a `502` (DEBT-0079); `--http1.1`
  works around it.
- Streams are relayed without backpressure: a fast sender fills the
  proxy's memory as fast as the slower side drains it, as M8.5's
  responses do.
- `make check` builds one more Netty module and runs six more tests,
  about a second.
