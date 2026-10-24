---
id: ADR-0069
title: The Ktor plugin captures in the send pipeline and masks by RFC 3986 on strings
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0069 — The Ktor plugin captures in the send pipeline and masks by RFC 3986 on strings

## Context

PLAN-0005 M4.5. `install(AppMonitorKtor)` (ADR-0062 A.1) must send one
exchange per request, redirect hops included, with the headers other
plugins add (`Cookie`, `Authorization`) and the shared masking rule pinned by
`protocol/fixtures/masking.json` on JVM, Android and iOS (ADR-0063 A.1).
`sdk/android-okhttp`'s `Masking` is built on OkHttp's `HttpUrl` and
`java.net.URLDecoder`, neither of which exists in Kotlin common code.

Evidence, Ktor 3.6.0 sources (Gradle cache, `ktor-client-core-jvm-3.6.0-sources.jar`,
`ktor-http-jvm-3.6.0-sources.jar`), read on 2026-10-24:

- `HttpSend` runs each hop through `client.sendPipeline.execute`
  (`plugins/HttpSend.kt:141`); `HttpRedirect` and `HttpRequestRetry` are
  `HttpSend` interceptors through the `Send` hook (`HttpRedirect.kt:109`,
  `HttpRequestRetry.kt:334`), so the send pipeline runs once per hop.
- `HttpCookies` sets `Cookie` at `HttpSendPipeline.State`
  (`plugins/cookies/HttpCookies.kt:131`); the engine runs at `Engine`
  (`engine/HttpClientEngine.kt:136`) and the client runs the receive
  pipeline, `SaveBody` included, at `Receive` (`HttpClient.kt:1370`). Ktor's
  own `Logging` intercepts `HttpSendPipeline.Monitoring`, between them
  (`ktor-client-logging/.../Logging.kt`, `SendHook`).
- `SaveBody` reads every non-streaming response into memory before the
  app sees it (`plugins/SaveBody.kt`); `HttpResponse.isSaved` is public;
  `readRawBytes()` is public and reads `rawContent` past the response
  pipeline (`statement/Readers.kt:34`). `rawContent` itself and
  `ObservableContent` (the upload progress wrapper) are `@InternalAPI`.
- `URLBuilder.takeFrom` resolves a path-only reference keeping the base's
  query (`URLParser.kt:37`, no reset of `parameters`) and does not remove
  dot segments, unlike RFC 3986 5.2 and `HttpUrl.resolve`.

## Options

### A. Where the plugin captures

1. **An interceptor at `HttpSendPipeline.Monitoring`** that builds
   `RequestStarted`, calls `proceed()`, and builds `ResponseReceived` from
   the returned call or `RequestFailed` from what it throws. Sees cookies
   and every header set before the engine; once per hop; the response is
   already saved. Same phase as `Logging`.
2. **The public `Send` hook** — one call per hop too, but it runs before
   the send pipeline, so `Cookie` from `HttpCookies` is not seen, against
   ADR-0063 A.
3. **`onRequest` / `onResponse`** — `onRequest` runs once per app call, not
   per hop, and before cookies.

### B. Bodies

1. **In-memory bodies only.** Request: `ByteArrayContent` (what `setBody`
   of a string, bytes or a serialized object becomes) cut at
   `maxBodyBytes`; a stream (`WriteChannelContent`, `ReadChannelContent`,
   multipart) not read, sent empty with `body_truncated` and its
   `contentLength`. Response: the saved body through `readRawBytes()`; a
   streamed response (`prepareGet().execute { }`) not read, same rule. The
   app's bytes are never consumed or delayed.
2. **Split every stream, as `Logging` does** — full bodies, but a second
   reader on the app's channel, a coroutine per body, and `@InternalAPI`
   calls.

### C. Masking in common code

1. **The rule as string operations on the URL as written**, the same
   functions as `sdk/android-okhttp`'s ADR-0044 fallback, plus a 40-line
   RFC 3986 5.2 resolver for relative `Location` and `Content-Location`
   values; a reference whose authority has no host or a bad port is left
   as written, as `HttpUrl.resolve` returning null does. One code path on
   every target, checked by the vector.
2. **Resolve with Ktor's `URLBuilder.takeFrom`** — no resolver to own, but
   `/next` against `…/a?id=1` keeps `id=1` (above): a URL the server never
   sent.
3. **Move `sdk/android-okhttp`'s `Masking` to `sdk/core` common** — one
   implementation, but it must first drop `HttpUrl`, changing what OkHttp
   apps see (its normalisation) in a PR about Ktor.

## Decision

A.1, B.1 and C.1. Two Kotlin implementations of one rule now exist,
kept equal by the shared vector; merging them is DEBT-0055.

## Consequences

- Upload progress (`onUpload`) wraps the body in `ObservableContent`, a
  stream: such a body is sent empty and truncated.
- A response that `ContentEncoding` decodes after `SaveBody` is captured
  as saved, encoded; engines that decompress themselves (OkHttp, Darwin)
  are unaffected.
- The saved response body is copied once more while it is cut.
- The vector is compiled into `commonTest` by a Gradle task, so the
  simulator reads it without a host path.
