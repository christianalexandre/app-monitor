---
id: ADR-0067
title: sdk/core iOS build, live simulator test, and stop() letting a connect end itself
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0067 — sdk/core iOS build, live simulator test, and stop() letting a connect end itself

## Context

PLAN-0005 M4.3 builds `sdk/core` for `iosArm64` and `iosSimulatorArm64`
(ADR-0062 E.1) as SDD 04's *Kotlin/Native (iOS)* designs it (ADR-0066).
Three questions came up while building it; ADR-0066's engine, JDK
replacements and common tests are not reopened.

Evidence, gathered on 2026-10-24 on this Mac (Xcode simulator, Kotlin
2.4.21, Ktor 3.6.0):

- `appmonitor.kmp-sdk` is applied by `sdk/core`, `sdk/android-okhttp` and
  `sdk/no-op`; the last two put OkHttp, a JVM library, in `commonMain`
  (`sdk/*/build.gradle.kts`), so they cannot gain iOS targets.
- Kotlin/Native simulator tests run under `xcrun simctl spawn`, which hands
  the test process only the variables prefixed `SIMCTL_CHILD_` (`xcrun simctl
  help spawn`). `SIMCTL_CHILD_APPMONITOR_LIVE_HOST=127.0.0.1` made
  `LiveServerTest` reach `make server`: its log printed
  `connected: iOS simulator (PLATFORM_IOS)`.
- `stopWhileConnectingClosesTheHalfOpenSocket` failed 1 run in 8 on
  `iosSimulatorArm64Test` and never on JVM: after `stop()`, the desktop's
  side read 0 bytes and no end of stream for 2 s. A bare ktor-network socket
  closed by its owner gives end of stream at once on the simulator, so the
  socket was never closed. CIO wraps the TCP connect in `withTimeoutOrNull`
  (`ktor-client-cio` 3.6.0 `Endpoint.kt`, `connect`); a cancellation landing
  as the connect returns drops the socket without closing it.
- `aDesktopThatStopsAnsweringPingsEndsInBackingOff` stayed in connected on
  JVM too: Ktor's pinger closes `incoming` with `IOException("Ping
  timeout")` (`ktor-websockets` 3.6.0 `DefaultWebSocketSession.kt`,
  `runOrCancelPinger`), the session block returns, but `HttpClient.webSocket`
  does not return while the desktop keeps its side of the socket open.
- `aDesktopThatDiesMidSessionEndsInBackingOff` passed on the simulator: writes
  to a socket the desktop closed end in backing off, no `SIGPIPE` (the open
  question of ADR-0066).

## Options

### A. Where the iOS targets are declared

1. **A second convention plugin, `appmonitor.kmp-sdk-ios`**, applying
   `appmonitor.kmp-sdk` and adding `iosArm64` and `iosSimulatorArm64`;
   `sdk/core` now, `sdk/ktor` and the core no-op later.
2. **iOS in `appmonitor.kmp-sdk`** — breaks the two OkHttp modules.
3. **Targets in each module's script** — the list repeated per module.

### B. The live test's host

1. **The Gradle task forwards `APPMONITOR_LIVE_HOST` as
   `SIMCTL_CHILD_APPMONITOR_LIVE_HOST`** and declares it as an input, so the
   live run is not skipped as up to date; the same variable as the Swift
   SDK's `LiveServerTests`.
2. **A Gradle property** — a second spelling for the same switch.

### C. `stop()` during connecting

1. **Let the connect end itself**: `stop()` does not cancel a client that is
   connecting or in a session; the connect either upgrades, sees the stop
   and closes 1000, or fails and the loop returns. The release's 1 s bound
   cancels whatever is still running, by then past the TCP connect.
2. **Cancel at once, as on JVM** — leaks a socket on iOS (above).

### D. A session block that returned

1. **The WebSocket call runs in its own coroutine; once the session's
   outcome is known it gets `closeTimeout` to finish, then is cancelled.**
2. **Wait for Ktor** — the client sits in connected, sending into a dead
   socket, until the OS gives up on the TCP connection.

## Decision

A.1, B.1, C.1, D.1.

## Consequences

- `stop()` while connecting now takes up to 1 s, still SDD 04's bound;
  `stop()` while discovering or backing off is still immediate.
- A connect that is still in its TCP phase after 1 s is cancelled and may
  leak its socket on iOS (DEBT-0053).
- D.1 fixes a hang on Android and the JVM too; SDD 04's connected exits
  were already written as if it worked.
- The no-op has no iOS dump to compare yet: `ApiMatchTest` compares
  `android` and `jvm` only, until M4.8 adds the core no-op (ADR-0062 C.1).
