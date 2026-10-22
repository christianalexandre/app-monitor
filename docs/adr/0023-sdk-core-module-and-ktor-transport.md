---
id: ADR-0023
title: sdk/core module layout and its Ktor CIO transport
status: Accepted
date: 2026-10-22
superseded_by:
---

# ADR-0023 — sdk/core module layout and its Ktor CIO transport

## Context

PLAN-0002 M1.5 writes the transport of `sdk/core` against SDD 04 and
ADR-0022. Already decided, not reopened here: Kotlin Multiplatform for the
Android and KMP SDKs, Swift for iOS (ADR-0003); SDK `minSdk` 24 and the
Ktor CIO engine as the reason for it (ADR-0017 D); cleartext blocked by the
app's policy ends in a log line naming the `debug-overrides` fix (ADR-0017
F); transport tests run against a real desktop server (guidelines 04).

Evidence, checked on 2026-10-22:

- `appmonitor.kmp-library` builds JVM, Android (`minSdk` 21) and iOS
  (`build-logic/`, ADR-0007). `sdk/core` has no iOS consumer.
- Android `NetworkSecurityPolicy.isCleartextTrafficPermitted()` (SDK
  sources, `android-37.0`): "there's no expectation that the
  `java.net.Socket` API will honor this flag". The host overload
  `isCleartextTrafficPermitted(String)` exists `since="24"`
  (`platforms/android-37.0/data/api-versions.xml`). Ktor CIO writes to raw
  sockets (`ktor-network`), so the policy is never applied to it.
- Ktor 3.6.0 client sources: `HttpClient.webSocketSession()` launches the
  session in the **client's** scope and only awaits it, so a caller
  cancelled or timed out mid-connect leaves the session running;
  `HttpClient.webSocket { }` runs the session inside the caller's coroutine
  and closes it when the block ends (`plugins/websocket/builders.kt`).
- Ktor 3.6.0 CIO: `requestTimeout` is `Long.MAX_VALUE` for WebSocket and
  upgrade requests (`EndpointKt.setupTimeout`, bytecode), so a long session
  is not cut at 15 s; the upgrade itself has no bound but the TCP connect.
- A Kotlin source set shared only by `jvm` and `android` compiles against the
  JDK: `synchronized`, `java.util.concurrent` and `System.currentTimeMillis`
  build in `commonMain` (`./gradlew :sdk:core:compileKotlinJvm
  :sdk:core:compileAndroidMain`).

## Options

1. **Module convention**: (a) reuse `appmonitor.kmp-library` — builds iOS
   for nothing and keeps `minSdk` 21; (b) a new `appmonitor.kmp-sdk`: JVM +
   Android, `minSdk` 24 from the catalog (`android-sdk-minSdk`),
   `explicitApi()`.
2. **Cleartext**: (a) rely on the platform — never fires with CIO, so the
   SDK would ignore an app that forbids cleartext, against ADR-0017 F;
   (b) ask `NetworkSecurityPolicy` for the host before connecting, through
   an `expect` function (`true` on the JVM).
3. **Session API**: (a) `webSocketSession()` — leaks a session on a
   timed-out or stopped connect; (b) `webSocket { }`, with a 10 s watchdog
   on the upgrade.
4. **Transport tests**: (a) `commonTest`, also on the Android host —
   `desktop:server` is a JVM module and `android.*` calls are stubs there;
   (b) `jvmTest` against an in-process `AppMonitorServer` (and a Ktor stub
   desktop for handshake states), with the platform hooks injected;
   pure logic (buffer, backoff) in `commonTest`.

## Decision

1(b), 2(b), 3(b), 4(b). The client is `internal` `MonitorClient`: one
coroutine runs the states of SDD 04; `stop()` lets an open session close
itself with 1000 and cancels any other state.

## Consequences

- `sdk/core` builds no iOS artifact; an iOS KMP consumer (roadmap) would
  need the iOS targets and a native replacement for the JDK calls.
- The Android cleartext check runs only on a device or emulator: no host
  test reaches `NetworkSecurityPolicy` (PLAN-0002 M1.11, M1.12 exercise it).
- The public entry point (`AppMonitor.start(context, options)`, ADR-0017)
  is not in this module yet; it lands with discovery (M1.6), which needs the
  Android `Context`.
