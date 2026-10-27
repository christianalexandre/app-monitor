---
id: ADR-0105
title: Netty and Bouncy Castle for the desktop proxy
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0105 — Netty and Bouncy Castle for the desktop proxy

## Context

PLAN-0009 M8.5 builds the proxy ADR-0101 decided: `127.0.0.1` only, a CA
per data directory with leaves minted per host, `CONNECT` with TLS
interception and plain HTTP, every exchange stored as `PROXY`. M8.6 adds
HTTP/2 and gRPC on the same proxy, so the HTTP layer chosen here has to
carry HTTP/2 frames next. Two primitives are missing from the desktop:

- **An HTTP/1.1 server and client that a `CONNECT` can turn into a TLS
  tunnel mid-connection.** Ktor's server (`desktop/server`,
  `desktop/mcp`) routes requests to handlers; it has no way to answer a
  `CONNECT`, take the raw socket back and start TLS on it.
- **X.509 certificate generation.** The JDK signs and verifies
  certificates but has no public API to build one: the builders live in
  `sun.security.x509`, not exported. `keytool` makes a CA, not a leaf per
  host on demand.

Evidence, from the official sources on 2026-10-25:

- Netty `io.netty:netty-codec-http` and `netty-handler`: the newest 4.x
  in `repo1.maven.org/maven2/io/netty/netty-codec-http/maven-metadata.xml`
  is `4.2.19.Final` (5.0.0 is `Alpha2`); `netty-parent-4.2.19.Final.pom`
  declares "Apache License, Version 2.0". The codecs this task uses:
  `HttpServerCodec`, `HttpClientCodec`, `SslHandler`, `OptionalSslHandler`;
  `netty-codec-http2` (same release) has the HTTP/2 frame codec and HPACK
  M8.6 needs.
- Bouncy Castle `org.bouncycastle:bcpkix-jdk18on`: newest release `1.86`
  in its `maven-metadata.xml`; the POM names the "Bouncy Castle Licence",
  whose text (`bcgit/bc-java`, `LICENSE.html`) is the MIT License.
  `JcaX509v3CertificateBuilder`, `JcaContentSignerBuilder`, `PEMParser`
  and `JcaPEMWriter` are the classes used; signing goes through the JDK's
  own providers, no provider is registered.

## Options

### A. HTTP and TLS

1. **Netty 4.2.** A codec per protocol that can be removed and added on
   a live connection, which is what a `CONNECT` needs; the same event
   loop drives the client side to the origin; HTTP/2 in the same release
   for M8.6. A new dependency of about 4 MB with its transport jars.
2. **JDK sockets and a hand-written HTTP/1.1 parser.** No dependency;
   chunked bodies, keep-alive, `100-continue` and upgrades are ours to get
   right, and M8.6's HTTP/2 (framing, HPACK, flow control) would be too.
3. **Ktor.** Already a dependency, but neither its server nor its client
   exposes the socket a `CONNECT` needs.

Recommended: 1, version `4.2.19.Final`.

### B. Certificates

1. **Bouncy Castle `bcpkix-jdk18on` 1.86.** The usual JVM builder for
   X.509 v3 with extensions (basic constraints, key usage, SAN, key
   identifiers) and PEM; about 10 MB with `bcprov` and `bcutil`.
2. **`sun.security.x509` through `--add-exports`.** No dependency; an
   internal API that changes between JDK releases and needs a flag in the
   packaged app.
3. **`keytool` as a subprocess.** No dependency; one process per new host
   and a keystore file per leaf.

Recommended: 1, version `1.86`.

### C. What the proxy writes

1. **A session of its own per start**, `Hello` with `device_name`
   `Proxy`, no device id, platform unspecified and `sdk_name` `proxy`; the
   exchanges are the events an SDK sends (`RequestStarted` once the
   request is in full, then `ResponseReceived` or `RequestFailed`), so
   every reader of SDD 09 and SDD 10 works unchanged. The session ends
   when the proxy stops.
2. **A new storage item for proxy exchanges** — a second ingest path for
   the same rows.

Recommended: 1.

## Decision

A.1 (Netty `4.2.19.Final`), B.1 (Bouncy Castle `bcpkix-jdk18on` `1.86`),
C.1 (a session per proxy start). Keys are EC P-256: the CA valid 365
days, leaves 30 days and never past the CA. The CA files are
`proxy-ca.pem` and `proxy-ca-key.pem` (PKCS#8) in the data directory,
written `0600` before their content. The port is `47476`,
`APPMONITOR_PROXY_PORT` moves it. Bodies keep at most
`Protocol.MAX_BODY_BYTES`, as an SDK's.

## Consequences

- The packaged app grows by about 14 MB (3.7 MB of Netty jars, 10.1 MB of
  Bouncy Castle, sizes of the resolved jars); both libraries need only
  `jdk.unsupported`, already in its modules.
- One request at a time per client connection: a pipelined request waits
  for the response before it; HTTP/1.1 clients rarely pipeline.
- A `101 Switching Protocols` hands both sides to a raw byte relay; what
  crosses after it is not captured (DEBT-0077). The pass-through list of
  ADR-0101 E.1 is not built yet (DEBT-0078).
