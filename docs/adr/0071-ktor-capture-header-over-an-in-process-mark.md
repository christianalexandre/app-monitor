---
id: ADR-0071
title: The Ktor plugin marks its captures with the header, not an in-process mark
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0071 — The Ktor plugin marks its captures with the header, not an in-process mark

## Context

PLAN-0005 M4.7. ADR-0064 A.1 picked the header `X-AppMonitor-Capture: ktor`,
added by `sdk/ktor` on iOS and skipped by the Swift resume hook, and asked
M4.7 to first try a mark that never leaves the device
(`URLProtocol.setProperty` on the Darwin engine's request): if it works
without new public API, it replaces the header.

Evidence, Ktor 3.6.0 sources (`ktor-client-darwin-3.6.0-sources.jar`, Gradle
cache), read on 2026-10-24:

- The `NSMutableURLRequest` is built from `HttpRequestData` (headers, body,
  timeouts) in `internal/DarwinRequestUtils.kt:14`, then handed to
  `DarwinClientEngineConfig.requestConfig` (`internal/DarwinSession.kt:37`);
  no plugin hook sees it, and request attributes are not copied into it.
- `requestConfig` is read per request and `configureRequest` appends to it
  (`DarwinClientEngineConfig.kt:35,83`), so a plugin holding
  `client.engine.config as? DarwinClientEngineConfig` could add the mark.
  That cast needs `ktor-client-darwin` on `sdk/ktor`'s iOS classpath, and
  Kotlin/Native links what it depends on: `Darwin.kt:12` registers the
  engine with `@EagerInitialization`, so an app on CIO that builds
  `HttpClient()` without an engine could start getting Darwin.
- Without that dependency, the mark needs the app to pass a block to
  `engine { configureRequest(…) }`: new public API and one more setup step.

Found while testing: the Swift hook skipped any WebSocket task made from a
`URLRequest` with a `ws` or `wss` URL — Foundation rewrites the scheme to
`http` only for `webSocketTask(with: URL)` (checked with a script on the
macOS host). Ktor's Darwin engine makes its tasks that way
(`webSocketTaskWithRequest`, `DarwinSession.kt:40`).

## Options

1. **In-process mark through `configureRequest`, with `ktor-client-darwin`
   as an iOS dependency of `sdk/ktor`** — nothing leaves the device; changes
   which engine `HttpClient()` picks in a CIO app, and marks every request
   of the client whether captured or not.
2. **In-process mark the app installs** — nothing leaves the device; new
   public API and a setup step the owner's rule sends to a `Proposed` ADR.
3. **The header of ADR-0064** — no new dependency or API; leaves the
   device on debug builds, as ADR-0064 accepted.

## Decision

Option 3: the header stands. `sdk/ktor` sets it (`set`, not appended) at
`HttpSendPipeline.Monitoring` on each hop it captures, on iOS only, and
removes it from a hop it does not capture, as `HttpRedirect` copies the
previous hop's headers. The captured `RequestStarted` shows it, as it is
sent. The Swift hook skips data, upload, download and WebSocket tasks whose
request carries it, whatever its value; it now also captures a WebSocket
task made from a `ws` or `wss` `URLRequest`, with the `http` or `https` URL
a task made from a URL has.

## Consequences

- The name lives in `Protocol.CAPTURE_HEADER` and
  `ProtocolConstants.captureHeader`; renaming it changes both SDKs.
- A request the app itself sends with the header, through any client, is
  not captured by the Swift SDK.
- Neither SDK proves the two together here; M4.10 runs the KMP sample on
  the simulator with both and asserts each exchange once.
