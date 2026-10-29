---
id: ADR-0159
title: OkHttp WebSockets are captured by the Gradle plugin, at OkHttpClient.newWebSocket
status: Accepted
date: 2026-10-28
superseded_by:
---

# ADR-0159 — OkHttp WebSockets are captured by the Gradle plugin, at OkHttpClient.newWebSocket

## Context

The plugin adds the interceptor to every OkHttp client of a debug build
(ADR-0112, ADR-0127), but frames still needed `AppMonitorWebSocketFactory`
in the app (ADR-0112 C.1). A KMP app on Ktor's OkHttp engine cannot use
the factory, since Ktor opens the socket itself. Measured on 2026-10-28
with `make e2e-kmp-android` and the sample's `install(AppMonitorKtor)`
removed: every HTTP call was captured through OkHttp, and the WebSocket
only as its `101` upgrade, with no frame. The owner asked for the KMP setup
to be the Android one.

- Every OkHttp socket goes through `OkHttpClient.newWebSocket`. That
  includes the app's own calls, Retrofit's, and Ktor's engine, which keeps
  the client as its `WebSocket.Factory`.
- The factory itself calls `delegate.newWebSocket` with a request tagged
  with its capture.

## Options

1. **Keep the factory as the only way.** One line per socket in Android
   apps; no frames for Ktor on OkHttp without the Ktor plugin.
2. **Rewrite every call site of `newWebSocket`** in the app and its
   libraries. That means two instructions to find
   (`invokevirtual OkHttpClient`, `invokeinterface WebSocket$Factory`), and
   a factory that is not a client would be wrapped too.
3. **Rewrite `OkHttpClient.newWebSocket` itself, as its constructor is.**
   It calls `AppMonitorOkHttp.newWebSocket(this, request, listener)` first
   and returns its socket when not null. That entry returns null while the
   monitor is idle, for a request tagged by the factory (the factory's own
   call), and for one the Ktor plugin marked (ADR-0129). Otherwise it opens
   the socket through `AppMonitorWebSocketFactory(client)`. The no-op
   returns null. AGP recomputes the method's frames
   (`COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS`).

## Decision

Option 3, under the standing owner decision "Short setup": it removes the
factory line, and a KMP app on OkHttp and Darwin installs nothing in
`commonMain`.

## Consequences

- `AppMonitorOkHttp.newWebSocket` joins the public API of
  `sdk/android-okhttp` and `sdk/okhttp-no-op` (dumps updated). The factory
  stays for apps that turn the rewrite off.
- `samples/android` has no WebSocket line, and `make e2e-android` still
  asserts its four frames. `samples/kmp` keeps `install(AppMonitorKtor)`, so
  the Ktor plugin stays covered end to end.
- Through OkHttp, a socket's URL is the upgrade's `http://`, as on iOS;
  through the Ktor plugin it is `ws://`.
- Tests: `ClientVisitorTest` (OkHttp's real client rewritten: the entry
  asked first, null opening the socket as usual, the entry's socket
  returned), `AppMonitorOkHttpWebSocketTest` (each null case, a socket
  captured), `OkHttpInjectionTest` (the call in the debug APK only).
