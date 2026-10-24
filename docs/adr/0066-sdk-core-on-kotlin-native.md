---
id: ADR-0066
title: sdk/core on Kotlin/Native keeps CIO and runs its state tests in common code
status: Accepted
date: 2026-10-24
superseded_by:
---

# ADR-0066 — sdk/core on Kotlin/Native keeps CIO and runs its state tests in common code

## Context

PLAN-0005 M4.2. M4.3 builds `sdk/core` for `iosArm64` and
`iosSimulatorArm64` (ADR-0062 E.1). M3's lesson: the iOS lifecycle and the
tests that drive it are written before the code. Three choices shape that
contract: the Ktor engine of the transport, what replaces the JDK calls of
`commonMain` (today it builds for JVM and Android only, so it compiles
`java.*`), and where the state machine tests run.

Already decided, not reopened here: the state machine, bounds, backoff and
buffer of SDD 04 (ADR-0022); Ktor 3.6.0 (ADR-0062 E.1); the device id file
shared with the Swift SDK (ADR-0064 B.1).

Evidence, read on 2026-10-24:

- `commonMain` of `sdk/core` uses `java.io.File`, `java.util.UUID`
  (`DeviceId.kt`), `java.io.IOException`, `java.util.concurrent.atomic.AtomicLong`,
  `System.currentTimeMillis` (`MonitorClient.kt`) and the JVM's
  `synchronized` (`MonitorClient.kt`, `MonitorHolder.kt`, `EventBuffer.kt`).
- Ktor 3.6.0's `CIOEngine` lives in the engine's `common` source set and
  declares `WebSocketCapability`
  (`ktor-client/ktor-client-cio/common/src/io/ktor/client/engine/cio/CIOEngine.kt:28-31`);
  `ktor-client-cio-iosarm64` and `-iossimulatorarm64` 3.6.0 are on Maven
  Central. The Darwin engine "uses `NSURLSession` internally" (`Darwin.kt:17`,
  ADR-0064).
- The Swift SDK captures every `URLSession` task of the process, WebSocket
  tasks included (ADR-0043, ADR-0045); its own transport is
  Network.framework, so it never sees itself (ADR-0040).
- `ktor-server-cio` and `ktor-server-websockets` 3.6.0 publish
  `iossimulatorarm64` variants (Maven Central), so a test desktop can run
  inside an iOS simulator test binary. `:desktop:server` is JVM only.
- `kotlinx.atomicfu.locks` declares a common `SynchronizedObject` and
  `synchronized(lock) { }` (kotlinx-atomicfu,
  `atomicfu/src/commonMain/kotlin/kotlinx/atomicfu/locks/Synchronized.common.kt:7,19`);
  atomicfu is already a dependency of `kotlinx-coroutines-core` 1.11.0
  (its Gradle module metadata) and is Apache-2.0.
- `kotlin.uuid.Uuid` is common stdlib API, stable "since Kotlin 2.4", and
  `kotlin.time.Clock` "since Kotlin 2.3", neither behind an opt-in
  (kotlinlang.org stdlib API pages); the catalog pins Kotlin 2.4.21.

## Options

### A. Engine on iOS

1. **CIO everywhere.** One engine in `commonMain`, as on Android; raw
   sockets, so neither ATS nor the Swift SDK's hooks see the monitor's own
   connection.
2. **Darwin on iOS.** The platform stack, but the monitor's WebSocket is a
   `URLSessionWebSocketTask`: with the Swift SDK in the same app, every
   handshake and envelope sent would be captured as app traffic and sent
   again, and ATS would govern `ws://` to the desktop.

### B. JDK calls

1. **Common replacements**: atomicfu `SynchronizedObject` and
   `synchronized`; the oversize counter under the client's lock;
   `kotlin.uuid.Uuid`; `kotlin.time.Clock.System`; `kotlinx.io.IOException`;
   the device id file behind an `expect` path, read and written with
   `kotlinx.io.files`.
2. **`expect`/`actual` per call** — the same code twice for locks and
   counters, for nothing a common library does not already give.

### C. Where the state tests run

1. **`commonTest` against a stand-in desktop** built on `ktor-server-cio`
   (WebSocket route, `Hello` in, `HelloAck` or a close out), so each test
   runs on JVM, the Android host and `iosSimulatorArm64`. Tests that need
   the real `:desktop:server` (approval, channel filter as the desktop
   applies it) stay in `jvmTest`.
2. **Keep `jvmTest`, add a separate iOS suite** — two copies of every case
   to keep in step.

## Decision

A.1, B.1, C.1. SDD 04's *Kotlin/Native (iOS)* section is the contract
M4.3 and M4.4 build, written ahead of the code and marked as such; the
task that builds a part of it removes its mark.

## Consequences

- One transport for Android and iOS; the monitor is never captured by the
  Swift SDK, whatever engine the app's own Ktor client uses.
- CIO on Kotlin/Native writes to BSD sockets: whether a write to a socket
  the desktop closed raises `SIGPIPE` is not verified, and is answered by
  SDD 04's named test on the simulator in M4.3.
- Local network privacy with BSD sockets on a physical iPhone is unproven
  (DEBT-0052).
- `sdk/core` gains a direct atomicfu dependency, already on its classpath.
