---
id: ADR-0070
title: The Ktor plugin records WebSocket frames on the engine's session
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0070 — The Ktor plugin records WebSocket frames on the engine's session

## Context

PLAN-0005 M4.6. ADR-0063 A.1 keeps WebSocket frames in scope: both ways and
the close, cut at `maxBodyBytes` as ADR-0028, `request_id` of the upgrade
that opened the session. Its consequence: find a frame route on the Darwin
and OkHttp engines, or name the engines covered. The upgrade itself is
already one exchange through the send pipeline (ADR-0069, `101`, no body).

Evidence, Ktor 3.6.0 sources (Gradle cache, `ktor-client-core-jvm`,
`ktor-websockets-jvm`, `ktor-client-okhttp-jvm`, `ktor-client-darwin`
`3.6.0-sources.jar`), read on 2026-10-24:

- `WebSocketExtension` sees every frame, but runs only on engines declaring
  `WebSocketExtensionsCapability` (CIO, not OkHttp or Darwin), and the app
  installs it in its own `WebSockets { extensions { } }` block (ADR-0063).
- The engine's session reaches the client as the call's body:
  `HttpClientCall.bodyNullable` runs the response pipeline on it
  (`call/HttpClientCall.kt:96`), and `WebSockets` turns it into the app's
  `DefaultClientWebSocketSession` at `HttpResponsePipeline.Transform`
  (`plugins/websocket/WebSockets.kt`, `convertSessionToDefault`), so a
  `Parse` interceptor sees it first, on every engine.
- CIO and Darwin hand over a raw session; `DefaultWebSocketSessionImpl`
  reads it with `raw.incoming.consumeEach` and writes every frame, pings and
  closes included, with `raw.outgoing.send` (`DefaultWebSocketSession.kt`).
  OkHttp hands over a `DefaultWebSocketSession` already
  (`OkHttpWebsocketSession`), used by the app as is: OkHttp answers the
  peer's close itself and puts no close frame in `incoming`, only completes
  `closeReason` before closing it (`onClosing`).
- `WebSocketSession.send` is an interface member writing to the session's
  own `outgoing`, so a delegating wrapper must override it.
- The `webSocket { }` builder sends a close on exit (`builders.kt`); CIO's
  writer closes its queue once a close is written (`WebSocketWriter.kt`),
  OkHttp ignores a second close: neither reaches the wire.
- Darwin's `didComplete` cancels the session's job, closing `incoming` with
  a `JobCancellationException`; when it runs before `didClose` queued the
  peer's close, the app's `incoming` fails with it, plugin installed or
  not (`DarwinWebsocketSession.kt`; seen in `DarwinWebSocketCaptureTest`'s
  uncaptured case in 5 of 10 runs).

## Options

1. **Wrap the engine's session at `HttpResponsePipeline.Parse`.** A
   delegating `WebSocketSession` (or `DefaultWebSocketSession` for OkHttp)
   whose `incoming` records each frame as its reader takes it and whose
   `outgoing` records each frame written. No coroutine of its own, no
   buffer, cancellation and close untouched. Covers CIO, OkHttp and Darwin
   with no app change. Raw sessions show what Ktor puts on the wire (pings,
   pongs and closes; a compressed payload when the app installed
   `WebSocketDeflateExtension`); on OkHttp, what the app sends and reads,
   the peer's close taken from `closeReason`.
2. **A `WebSocketExtension`** — the app's frames uncompressed, but CIO only
   and a line in the app's `WebSockets` block, a public API change
   (ADR-0062).
3. **Wrap the app's `DefaultClientWebSocketSession` after `Transform`** —
   every engine, but pings and closes are handled inside Ktor and never seen,
   and the session is a final class the wrapper cannot pass for.
4. **A relay coroutine copying the engine's `incoming`** — tried first;
   launched in the engine session's scope, Darwin's job cancellation dropped
   the close frame the relay held, failing the app's session.

## Decision

Option 1. A session records at most one sent close (RFC 6455 5.5.1), none
once the engine's channel is closed, and on OkHttp none after the peer's
close, so a close that never reaches the wire is not shown.

## Consequences

- One test runs against an in-process CIO echo server per engine: CIO in
  `commonTest` (JVM, Android host, iOS simulator), OkHttp in `jvmTest`,
  Darwin in `iosTest`. The Darwin case accepts a lost close, as the app
  would see it.
- Frames received through `select { incoming.onReceive }` on an OkHttp
  session are not recorded; Ktor's own readers and `for`, `receive`,
  `receiveCatching`, `tryReceive`, `consumeEach` are.
- On CIO and Darwin, keep-alive pings and pongs are captured when the app
  sets a ping interval; OkHttp keeps them inside OkHttp, as DEBT-0029.
- `sdk/ktor` depends on `kotlinx-atomicfu`, already on `sdk/core`'s runtime
  path; tests add `ktor-client-cio`, `ktor-client-okhttp`,
  `ktor-client-darwin`, `ktor-server-cio` and `ktor-server-websockets`
  (Apache-2.0).
