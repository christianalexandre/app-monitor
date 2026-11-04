---
id: ADR-0195
title: A tunnel's leaf offers what its origin does, asked before the client's handshake
status: Accepted # Proposed | Accepted | Superseded | Rejected
date: 2026-11-04
superseded_by:
---

# ADR-0195 — A tunnel's leaf offers what its origin does, asked before the client's handshake

## Context

Inside a TLS tunnel the leaf offered ALPN `h2` first, so curl and most
HTTP/2-capable clients picked it, and ADR-0106 C.1 then spoke HTTP/2 to
the origin. An origin without `h2` made each stream a `502`, `<host>:<port>
does not offer HTTP/2` (DEBT-0079, `Http2ProxyTest`), though the same
client reaches that origin without the proxy. ADR-0106 is not reopened
for an origin that does offer `h2`.

Forces:

- ALPN is settled in the client's handshake, before any request, so a
  client moved to HTTP/1.1 must be told then.
- The HTTP/1.1 relay already handles every such exchange (SDD 11), bodies
  and backpressure included.
- `CONNECT` is answered before the first TLS byte, and a plain tunnel's
  origin may never answer a handshake (`Http2ProxyTest`'s gRPC calls run
  on a plaintext port), so the origin can only be asked once the client's
  first bytes show TLS.

## Options

1. **Ask the origin first.** When a tunnel's first bytes are TLS, one
   handshake of the proxy's own with the origin, ALPN `h2` and `http/1.1`;
   the leaf then offers `h2` and `http/1.1`, or no ALPN when the origin
   chose no `h2`. A client sees what it would see without the proxy, and
   no new relay. One more connection per origin, and the client's handshake
   waits for it.
2. **Translate each stream.** Keep `h2` towards the client and relay its
   streams over HTTP/1.1 connections to the origin, through
   `Http2StreamFrameToHttpObjectCodec`. Serves a client offering only `h2`,
   which could not reach this origin without the proxy either. A third relay
   path, with its own connection pool, backpressure and capture, and headers
   the origin receives differ from the client's.
3. **Learn from the failure.** Keep the `502` and remember the origin for
   the next tunnel. No extra connection, but the first request of every
   origin fails, a retried `POST` included, and the client sees an error it
   would not see without the proxy.

## Decision

Option 1, in `OriginAlpn` and `TunnelTls`. The answer is kept per host and
port for the proxy's life. No answer within 3 s, a refused connection or a
refused handshake counts as `h2` and is not kept: the leaf offers what it
always did and the relay reports the reason as before, so a failed question
adds no failure of its own. `does not offer HTTP/2` stays for an origin
that stops offering `h2` after it was asked, or that was too slow to answer:
that stream gets the `502`, and the next tunnel goes over HTTP/1.1.

## Consequences

- An HTTP/2 client reaches an HTTP/1.1-only origin with no flag, stored as
  any HTTP/1.1 exchange. `make e2e-ios-grpc` shows gRPC-Core's TLS call
  through the same step.
- Each origin sees one more TLS connection, closed once negotiated, the first
  time a proxy run tunnels to it; a client's first handshake with that origin
  waits for that question, 3 s at most.
- An origin that gains `h2` stays on HTTP/1.1 until the proxy restarts:
  it works, only with a different protocol than it could use.
- A client that speaks only `h2` to such an origin cannot be served, as it could
  not be without the proxy: with a prior-knowledge preface it gets the `502`
  (`Http2ProxyTest`).
