---
id: DEBT-0079
title: An HTTP/2 client cannot reach an HTTP/1.1-only origin through the proxy
status: Open
date: 2026-10-25
trigger: "A developer's HTTPS origin without HTTP/2 answering 502 through the proxy to a client that cannot be told to use HTTP/1.1"
paid_date:
---

# DEBT-0079 — An HTTP/2 client cannot reach an HTTP/1.1-only origin through the proxy

## What is missing

Inside a TLS tunnel the proxy's leaf offers ALPN `h2` first, so curl and
most HTTP/2-capable clients pick it. The proxy then talks HTTP/2 to the
origin (ADR-0106 C.1); an origin that does not offer `h2` makes the
stream a `502` and a failed exchange, `<host>:<port> does not offer
HTTP/2` (`Http2ProxyTest`). Before M8.6 the same request went through as
HTTP/1.1.

## Why it was accepted

ADR-0101 B.1 feeds the proxy with gRPC-Core traffic, which is HTTP/2 end
to end. Translating an HTTP/2 stream to HTTP/1.1 is a third relay path.
The failure is visible and names the cause, and `curl --http1.1` works
around it.

## Trigger

A developer's HTTPS origin without HTTP/2 answering 502 through the proxy to a client that cannot be told to use HTTP/1.1.

Fired by the PLAN-0009 milestone review. Not paid: no such origin has been reported. Re-triggered on that event alone.

## How it was paid

> Filled when closing.
