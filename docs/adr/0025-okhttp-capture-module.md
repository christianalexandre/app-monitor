---
id: ADR-0025
title: OkHttp capture module, its bridge to sdk/core and the body cap
status: Accepted
date: 2026-10-22
superseded_by:
---

# ADR-0025 — OkHttp capture module, its bridge to sdk/core and the body cap

## Context

PLAN-0002 M1.7 writes `sdk/android-okhttp`. Already decided, not reopened
here: the public types `AppMonitorInterceptor()` and
`AppMonitorWebSocketFactory(client)` (ADR-0017 C); masking, the 1 MiB body
limit and "copied as the app reads" (ADR-0018 B, C); the body fields
(ADR-0020); `sdk/core` as JVM + Android with `explicitApi()` (ADR-0023).
The capture module must reach the running monitor, which lives in another
module, without widening the API an app developer sees.

Evidence, checked on 2026-10-22:

- OkHttp 5 publishes Gradle module metadata with `jvm` (`okhttp-jvm`) and
  `androidJvm` (`okhttp-android`) variants (`okhttp-5.5.1.module`); a
  `commonMain` shared by `jvm` and `android` compiles against it
  (`./gradlew :sdk:android-okhttp:compileKotlinJvm
  :sdk:android-okhttp:compileAndroidMain`). `mockwebserver3` is the
  matching test server, same version.
- The `okhttp-android` AAR metadata (`aar-metadata.properties`) sets
  `minCompileSdk=37` in 5.5.0 and 5.5.1 (latest), `36` in 5.4.0, `1` in
  5.3.2: AGP fails a consumer compiled against a lower SDK (it failed this
  repository's `make check`, `compileSdk` 36).
- Kotlin `internal` is per module; `@RequiresOptIn(level = ERROR)` makes a
  public declaration a compile error for callers that do not opt in
  (kotlinlang.org, "Opt-in requirements").
- OkHttp `HttpLoggingInterceptor` 5.5.1 compares redacted header and query
  parameter names with `String.CASE_INSENSITIVE_ORDER`.
- WebSocket frames never pass through interceptors (ADR-0017); the upgrade
  request does, and `Request.newBuilder()` keeps the request's tags.
- `Protocol.MAX_FRAME_BYTES` is one `MAX_BODY_BYTES` body plus 256 KiB; a
  larger envelope is dropped by `MonitorClient.send` before the buffer
  (SDD 04), so it no longer ends the session (DEBT-0021's symptom).

## Options

1. **Module convention**: (a) a plain Android library — a new convention,
   and MockWebServer tests on the Android host; (b) `appmonitor.kmp-sdk`
   (JVM + Android), code in `commonMain`, MockWebServer tests in `jvmTest`.
2. **Reaching the monitor**: (a) make `MonitorHolder` public — apps see
   it; (b) a public `MonitorBridge` (options + `send`) behind a
   `RequiresOptIn` annotation at error level, that capture modules opt in to
   and `sdk/no-op` does not mirror; (c) compiler friend paths — not a
   supported Gradle setting.
3. **OkHttp dependency**: (a) `compileOnly` — the app's own OkHttp at
   runtime, but nothing stops an OkHttp 4 app from missing a 5.x method;
   (b) `api` — the public types extend OkHttp's, and Gradle resolves the
   app's OkHttp to at least the SDK's version in builds with the debug
   artifact. The version: 5.5.1 would force `compileSdk` 37 on every app;
   5.3.2 is the newest that forces none, and an app on a newer OkHttp keeps
   its own.
4. **`maxBodyBytes` above the protocol limit (DEBT-0021)**: (a) let the
   desktop announce its limit in `HelloAck` — a protocol change for a value
   both ends already share in `Protocol`; (b) reject `maxBodyBytes` above
   `Protocol.MAX_BODY_BYTES` in `AppMonitorOptions`; an envelope whose URL
   and headers still push it past the frame limit is dropped whole.
5. **When `ResponseReceived` is sent**: it carries the body, so it is sent
   when the app reaches the end of the body or closes it; at once for a
   `Content-Length: 0` body and for `101`. A body the app never reads nor
   closes sends nothing (OkHttp already reports such a leak).
6. **WebSocket upgrade**: the factory sends `RequestStarted` (masked) and
   `ResponseReceived` on `onOpen`, tags the request, and the interceptor
   skips tagged requests, so an exchange is recorded once whether or not the
   client also has the interceptor.

## Decision

1(b), 2(b), 3(b) with OkHttp 5.3.2, 4(b), 5 and 6. Request ids are random UUIDs. Capture
code never throws into the app's call: a failure building an event drops
that event.

## Consequences

- `sdk/android-okhttp` brings `sdk/core` as `api`: an app adds one
  dependency and calls `AppMonitor.start`.
- An app below OkHttp 5.3.2 gets 5.3.2 in its debug build only; release,
  with `sdk/no-op`, keeps its own version. Raising the pin past 5.3.2 raises
  the `compileSdk` every app needs.
- `sdk/no-op` (M1.8) mirrors the public API minus `InternalAppMonitorApi`
  declarations.
- `maxBodyBytes` is now an upper-bounded option; DEBT-0021 is paid.
- WebSocket payloads are cut at `maxBodyBytes` without a marker, and ping
  and pong frames are not visible through OkHttp's API (DEBT-0025).
