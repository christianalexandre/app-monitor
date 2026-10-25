---
id: ADR-0128
title: As a network interceptor, the OkHttp capture serves no mock and keeps the host
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0128 — As a network interceptor, the OkHttp capture serves no mock and keeps the host

## Context

`AppMonitorInterceptor`'s KDoc allows `addNetworkInterceptor` (ADR-0017).
ADR-0115 answers a mocked call without `chain.proceed`, and ADR-0123
applies a request breakpoint's resume, URL included, to the request
passed on. OkHttp 5.3.2's `RealInterceptorChain.proceed` (sources jar,
`okhttp3/internal/http/RealInterceptorChain.kt`, lines 112 to 131)
checks, only when the chain has an exchange (a network interceptor's):
the request keeps the host and port ("must retain the same host and
port"), and `proceed` runs exactly once ("must call proceed() exactly
once"). Both throw `IllegalStateException`, which the call rethrows: the
app crashes. `MockAnswerTest` and `BreakpointTest` reproduced both
before this change. `Interceptor.Chain.connection()` is non-null only in
a network interceptor's chain (its KDoc, `okhttp3/Interceptor.kt`).

## Options

1. **Serve no mock and refuse a resume to another host or port as a
   network interceptor.** `connection() != null` tells the two apart. A
   matching call goes to the network, captured as any other; a resume
   that moves the host or port is a refused edit (ADR-0123: the call
   fails with an `IOException`). Small; the application interceptor,
   the setup the README and the Gradle plugin use, is unchanged.
2. **Answer the mock after a `proceed`.** The real request still goes
   out: a mock would no longer mean "without the network" (ADR-0110).
3. **Stop capturing as a network interceptor.** Drops what ADR-0017
   documents (one event set per hop, real headers), for two features.

## Decision

Option 1. A refused resume reads "Breakpoint edit refused: a network
interceptor keeps the host and port". No public API changes; the KDoc
says it.

## Consequences

- An app that adds the interceptor with `addNetworkInterceptor` sees
  its calls go to the network even when a rule matches, and the desktop
  shows them unmocked. A breakpoint, a response edit and a replay work
  as before.
- A path, method, header or body edit on the same host still goes out.
