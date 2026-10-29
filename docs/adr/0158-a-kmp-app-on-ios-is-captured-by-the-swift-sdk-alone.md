---
id: ADR-0158
title: A KMP app on iOS is captured by the Swift SDK alone
status: Accepted
date: 2026-10-28
superseded_by:
---

# ADR-0158 — A KMP app on iOS is captured by the Swift SDK alone

## Context

The KMP setup started two clients on iOS: the Swift SDK in the app, and
`sdk/core` through an `iosMain` function the app called (ADR-0062 B.1).
Ktor's captures carried a header so the Swift SDK skipped them (ADR-0064).
The first field test needed `appmonitor-core` in `iosMain`, a swap for it,
and two start calls.

- Ktor's Darwin engine runs on `URLSession`, and the Ktor plugin marks
  nothing until a Kotlin `AppMonitor.start` runs (`AppMonitorKtor.kt`). So
  without that start, the Swift SDK sees each call as any other task.
- Measured with `make e2e-kmp-ios`: everything was captured once but the
  WebSocket's received frame. Ktor asks `receiveMessage` from its session's
  `init`, before `DarwinSession.kt` resumes the task (ktor 3.6.0
  `DarwinWebsocketSession.kt`). The Swift SDK starts tracking a WebSocket
  task at resume, so its hook found no capture and passed the handler
  through. Any app that asks before resuming loses that message.

## Options

1. **Two clients, as ADR-0062 B.1.** Two starts, two sessions, `sdk/core`
   in `iosMain`.
2. **The Swift SDK alone on iOS.** The app starts only the Swift SDK; Ktor
   on iOS goes through `URLSession`. The receive hook wraps every handler
   and looks the capture up when the message arrives.

## Decision

Option 2. Kotlin's `AppMonitor.start` on iOS stays in the API (ADR-0062);
the README no longer asks for it.

## Consequences

- A KMP app adds the Swift package and starts it, as a Swift app does. The
  Kotlin side needs `appmonitor-ktor` (the plugin adds it) and nothing in
  `iosMain`.
- `make e2e-kmp-ios` asserts one Swift SDK session, no `sdk/core` one, and
  each exchange once. On iOS the WebSocket is `http://` there, as
  `URLSession` reports it.
- `aReceiveAskedBeforeResumeIsCaptured` covers the hook.
- An iOS app that wants Ktor's own view (its plugins' headers before
  `URLSession`) can still start `sdk/core`. The dedupe of ADR-0064 then
  applies.
