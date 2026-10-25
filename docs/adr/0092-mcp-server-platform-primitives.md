---
id: ADR-0092
title: MCP server on the Kotlin MCP SDK 0.15.0, stateless Streamable HTTP on Ktor CIO
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0092 — MCP server on the Kotlin MCP SDK 0.15.0, stateless Streamable HTTP on Ktor CIO

## Context

PLAN-0007 M6.2. ADR-0005 put a Streamable HTTP MCP server in the desktop
process; ADR-0089, ADR-0090 and ADR-0091 fixed what it returns, who may
call it and which clients it serves. This ADR lists the primitives
`desktop/mcp` relies on, with their evidence, before the code (M6.3).
SDD 10 is the design built on them.

Evidence, read on 2026-10-24:

- **Version.** Maven Central `maven-metadata.xml` for
  `io.modelcontextprotocol:kotlin-sdk-server-jvm`: latest and release
  `0.15.0` (last updated 2026-07-28); the GitHub tag `0.15.0` exists.
  `kotlin-sdk-client-jvm` is at the same version.
- **Licence.** The `0.15.0` POMs of `kotlin-sdk-server-jvm`,
  `kotlin-sdk-core-jvm` and `kotlin-sdk-client-jvm` declare the MIT
  License. The repository's `LICENSE` at `0.15.0` says the project "is
  undergoing a licensing transition from the MIT License to the Apache
  License, Version 2.0": new code is Apache-2.0, the rest MIT. Both are on
  `AGENTS.md`'s allowed list.
- **What it pulls.** `kotlin-sdk-server-jvm:0.15.0` POM: compile
  `kotlin-sdk-core`, `ktor-server-core` and `ktor-server-sse` `3.5.1`,
  `kotlin-stdlib` `2.4.0`; runtime `ktor-server-content-negotiation`,
  `ktor-server-websockets`, `ktor-serialization-kotlinx-json` `3.5.1`.
  `kotlin-sdk-core-jvm`: `kotlinx-serialization-json` `1.11.0`,
  `kotlinx-coroutines-core` `1.11.0`, `kotlinx-io-core` `0.9.1`,
  `kotlinx-collections-immutable` `0.5.1`, runtime
  `io.github.oshai:kotlin-logging-jvm` `8.0.4`. The client adds
  `ktor-client-core` `3.5.1`. The README: "it does not add Ktor engine
  dependencies transitively".
- **Ktor.** `desktop/server` uses Ktor `3.6.0` (`gradle/libs.versions.toml`)
  with the CIO engine; Gradle resolves the SDK's `3.5.1` requests to
  `3.6.0`, a minor version up.
- **Streamable HTTP helpers** (`KtorServer.kt` at `0.15.0`):
  `Application.mcpStreamableHttp(path = "/mcp", …)` keeps a transport per
  `Mcp-Session-Id` and answers `GET` with an SSE stream;
  `Application.mcpStatelessStreamableHttp(path = "/mcp", …)` serves `POST`
  only, answers `GET` and `DELETE` with `405`, and for each request
  creates a session on the given `Server`, handles the request and closes
  the session ("A stateless session serves exactly one request"). Both set
  `enableJsonResponse = true` and install `ContentNegotiation` with
  `McpJson` themselves.
- **The spec allows a stateless server.** "A server using the Streamable
  HTTP transport **MAY** assign a session ID"; on `GET` "The server **MUST**
  either return `Content-Type: text/event-stream` … or else return HTTP 405
  Method Not Allowed"; `DELETE` "**MAY**" get `405`
  ([MCP spec 2025-06-18, Transports](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)).
  The SDK speaks `2025-11-25`, `2025-06-18`, `2025-03-26` and `2024-11-05`
  (`SUPPORTED_PROTOCOL_VERSIONS`, `types/common.kt`).
- **DNS rebinding.** Both helpers install the SDK's `DnsRebindingProtection`
  by default: `Host` must be `localhost`, `127.0.0.1` or `[::1]`, and an
  `Origin`, when present, one of those hosts; the comparison is
  "port-agnostic" and "scheme-agnostic" (`HostValidation.kt`), and a
  refusal is `403` with a JSON-RPC error.
- **JDK modules.** `jdeps --list-deps` (JDK 21): the SDK's core, server and
  client jars, `ktor-server-sse`, `ktor-server-content-negotiation`,
  `ktor-serialization-kotlinx(-json)` and `kotlinx-collections-immutable`
  need `java.base` only; `kotlin-logging-jvm` needs `java.base` and
  `java.logging`. `java.sql`, already packaged (ADR-0085),
  `requires transitive java.logging` (`java --describe-module java.sql`).
  `slf4j-api` is already on `desktop/app`'s runtime classpath through Ktor.

## Options

1. **Kotlin MCP SDK 0.15.0, stateless Streamable HTTP, on its own Ktor CIO
   server** — `mcpStatelessStreamableHttp` on an `embeddedServer(CIO)`
   bound to `127.0.0.1`, apart from the device server (ADR-0090). No
   session table: nothing to leak when a client vanishes, nothing to drain
   at stop but the requests in flight. The tools are read-only and their
   list never changes, so the server never needs to push to a client.
2. **Same SDK, stateful `mcpStreamableHttp`** — sessions and an SSE stream
   per client; useful for notifications and resumability, which no tool
   needs, at the cost of a session table that stop and port changes must
   clean up.
3. **JSON-RPC by hand on Ktor** — no dependency, but the protocol's
   initialization, versions and schemas written and kept up to date by us.

## Decision

Option 1:

- `io.modelcontextprotocol:kotlin-sdk-server` `0.15.0` in `desktop/mcp`,
  `kotlin-sdk-client` `0.15.0` in its tests only, Ktor at the project's
  `3.6.0` (`ktor-server-cio`, plus `ktor-client-cio` for the test client).
- `Application.mcpStatelessStreamableHttp(path = "/mcp")` with the SDK's
  `DnsRebindingProtection` left on; our own exact `Host`/`Origin` check
  with the port, and the bearer token (ADR-0090 A.1, B.2), run before it,
  because the SDK's check ignores the port and accepts `[::1]`.
- One `Server` built at start with every tool registered; the helper opens
  and closes a session on it per request.
- No new JDK module in `nativeDistributions.modules`; M6.8's packaged run
  checks it.

## Consequences

- The SDK runs on Ktor `3.6.0`, not the `3.5.1` it was built against;
  M6.3's test (a real SDK client over HTTP) is the evidence that the pair
  works. A break pins Ktor or waits for an SDK release.
- The SDK is pre-1.0: a minor version may change its API. It is pinned in
  `gradle/libs.versions.toml` like every other library.
- No server-to-client notifications: a future tool that streams progress
  or a resource subscription needs option 2 (a new ADR).
