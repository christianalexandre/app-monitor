---
id: ADR-0122
title: How the Kotlin SDKs keep and send a request again
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0122 — How the Kotlin SDKs keep and send a request again

## Context

ADR-0111 D.1 decided that a replay goes through the app: on
`ReplayRequest(request_id)` the SDK sends that call again through the
app's own client (`Call.clone()`, the plugin's `HttpClient`), from the
last 200 requests it keeps, refuses an older one or one of a past session
with a reason, and captures the new call with `replay_of`. ADR-0120 fixed
the wire (`ReplayRefused`, `RequestStarted.replay_of`). Left open, inside
the agreed architecture (`AGENTS.md`): where `sdk/core` keeps the
requests, what each module keeps and from which point it sends again,
and how an end to end asks for a replay without the desktop window.

Already decided, not reopened here: the session's state lives in its
`MonitorClient` and goes with the session (ADR-0115 A.1); a capture module
reaches it through `MonitorBridge`, behind `@InternalAppMonitorApi`
(ADR-0025).

Evidence, 2026-10-25, OkHttp 5.3.2 and Ktor 3.6.0
(`ktor-client-core-jvm-3.6.0-sources.jar`):

- `Interceptor.Chain.call()` is the app's `Call`, and `Call.clone()` a new
  one on the same client, from the same original request, before any
  application interceptor (ADR-0111's evidence). In a clone the
  interceptor sees `chain.call()` as the clone, so a map keyed by the
  clone tells it the call is a replay: `RealCall` overrides neither
  `equals` nor `hashCode` (`javap` on `okhttp-jvm-5.3.2.jar`).
- `RequestBody.isOneShot()` and `isDuplex()` mark a body that cannot be
  written twice (ADR-0018 C already skips reading them).
- Ktor: `HttpSend` calls `context.setBody(content)` before the send
  pipeline (`plugins/HttpSend.kt:91`), so at `HttpSendPipeline.Monitoring`
  the builder holds the body every plugin already transformed, and
  headers that `DefaultRequest`, `HttpCookies` or `UserAgent` added. A
  plugin intercepting `HttpRequestPipeline.Before` sees the builder as
  the app made it. `HttpRequestBuilder.takeFrom` is public and copies
  method, URL, headers, body, body type and attributes, not the execution
  context (`request/HttpRequest.kt:183`). `ClientUpgradeContent` is
  `@InternalAPI`; a WebSocket request has a `ws` or `wss` URL
  (`plugins/websocket/WebSockets.kt:205`).
- `HttpClient`'s own `Job` is a plain `Job(engine's)`
  (`HttpClient.kt:1312`): an exception thrown in a coroutine launched on
  the client would cancel the client.
- `make e2e-android` on emulator-5554, this task: the headless server
  asked a replay of the sample's `GET /json` once its `RequestStarted`
  arrived, and the device sent it again, captured with `replay_of` and a
  `ResponseReceived`.

## Options

### A. Where `sdk/core` keeps the requests

1. **`MonitorClient.replays`, the last 200 by request id, emptied when the
   session ends.** A capture module hands a closure per request
   (`MonitorBridge.keepForReplay(id, replay)`), which returns null once
   the call is on its way, else the reason `ReplayRefused` carries. The
   client answers a `ReplayRequest` itself, so each module only knows how
   to send its own call again. "One of a past session" (ADR-0111 D.1) is
   whatever the session that ended had kept.
2. **Each module keeps its own map and reads `ReplayRequest`.** Two
   bounded maps and two readers of the channel, for no case A.1 misses.

### B. What OkHttp sends again

1. **A clone of the app's `Call`, enqueued, its body read to the end.**
   The same client, interceptors, cookie jar and TLS settings; the
   response read so `CapturingSource` captures it whole, then closed. A
   one-shot or duplex body is refused.
2. **`OkHttpClient.newCall(chain.request())`.** The interceptor does not
   have the client, and `chain.request()` already went through the
   application interceptors before it, which would run twice.

### C. What Ktor sends again

1. **The builder as the app made it, copied at
   `HttpRequestPipeline.Before`, run again by `client.request(...)` on
   the client's scope.** Every plugin runs once more, as for the app's own
   call, so a header a plugin adds is not doubled and a cookie is the
   jar's current one. The launched coroutine catches every exception: the
   call's failure is captured by the send pipeline already. A streamed
   body (`ReadChannelContent`), a WebSocket and a closed client are
   refused.
2. **The builder seen at `Monitoring`, sent again.** Headers added by
   plugins would go through those plugins a second time.

### D. How an end to end asks for a replay

1. **`make server --replay PATH`: the first request of each session to
   that URL path, not itself a replay, is asked again.** The Android end
   to end asks for the sample's first call, and its line asserts a
   `RequestStarted` with `replay_of` and its terminal event. The
   duplicate check ignores replays.
2. **The desktop app driven offscreen.** No end to end runs the window.

## Decision

A.1, B.1, C.1, D.1. The iOS SDK (PLAN-0010 M9.11) follows A.1 with its
own `MonitorClient`.

## Consequences

- The kept closures hold the app's `Call` or request builder, bodies
  included, for at most 200 requests per session.
- A replay of a redirect hop or a network interceptor's hop sends the
  whole call again from its first request.
- `RequestStarted.replay_of` is on the wire from the Kotlin SDKs; the
  desktop stores and links it already (ADR-0120 B.1).
