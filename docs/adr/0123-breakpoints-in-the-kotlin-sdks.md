---
id: ADR-0123
title: How the Kotlin SDKs hold a call at a breakpoint
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0123 — How the Kotlin SDKs hold a call at a breakpoint

## Context

ADR-0111 decided what a breakpoint does on the device: hold the request
before it is sent and/or the response before the app reads it, send
`BreakpointHit`, wait for `BreakpointResume` or `BreakpointAbort`, go on
unchanged after the timeout or at once when the session ends, and apply
only the fields a resume carries. ADR-0118 fixed the wire: a hold named by
request id and stage, `optional` fields, `set_headers` and
`removed_headers` by name. Left open, inside the agreed architecture
(`AGENTS.md`): where `sdk/core` waits, how OkHttp's blocking interceptor
and Ktor's pipeline hold a call, how a hold sits with a mock, what is
captured, and what an edit the client refuses does (PLAN-0010 M9.14).

Already decided, not reopened here: the session's state lives in its
`MonitorClient` (ADR-0115 A.1, ADR-0122 A.1); a capture module reaches it
through `MonitorBridge`, behind `@InternalAppMonitorApi` (ADR-0025); a
mocked Ktor call is built with Ktor's internal API (ADR-0115 C.1,
DEBT-0083).

Evidence, 2026-10-25, OkHttp 5.3.2 and Ktor 3.6.0:

- OkHttp's `Interceptor.intercept` is blocking and returns a `Response`;
  `Call.isCanceled()` tells it the app cancelled the call, as the mock's
  delay already checks (`AppMonitorInterceptor.answer`).
  `BreakpointTest.aHeldCallTheAppCancelsFails` in `sdk/android-okhttp`.
- `BufferedSource.peek()` reads ahead without consuming, so the app
  still reads the whole body:
  `BreakpointTest.anUnchangedResponseIsReadWholeAfterItsHeldPart`.
- Ktor's `SuspendFunctionGun` resumes the pipeline's caller with the
  subject as it is when the last interceptor returns, so an interceptor
  that sets `subject` after its `proceed()` replaces the call
  (`ktor-utils-jvm-3.6.0-sources.jar`, `SuspendFunctionGun.loop` and
  `resumeRootWith`); `BreakpointTest` in `sdk/ktor` on JVM, Android
  host and the simulator.
- `HttpSendPipeline.Receive` runs the receive pipeline inside the
  `Monitoring` interceptor's `proceed()` (`HttpClient.kt:1370`), so a
  call replaced after it is read without `SaveBody`, as a mocked call is.

## Options

### A. Where the device waits

1. **`sdk/core`'s `Breakpoints`, in the session's `MonitorClient`.**
   `MonitorBridge.breakpointFor(method, url, stage)` matches as
   `mockFor` does, the first rule holding that stage; `MonitorBridge.hold(hit)`
   sends the hit and suspends until the resume, the abort, the hit's
   timeout or the session's end, which lets every held call go on
   unchanged. A module builds the hit, masked as its capture.
2. **Each module keeps its own held calls.** Two maps and two readers of
   `BreakpointResume`, as ADR-0122 A.2.

### B. How OkHttp waits

1. **`runBlocking` on the call's thread, checking `Call.isCanceled()`
   every 50 ms.** A cancel fails the call with `IOException("Canceled")`,
   an interrupt with `InterruptedIOException`, as a mock's delay does
   (ADR-0115 D.1). A held response is peeked up to `maxBodyBytes`.
2. **Only the timeout ends a wait.** An app that cancels a held call
   would keep its thread blocked up to ten minutes.

### C. The order of a hold, a mock and the capture

1. **Request hold, then the mock lookup on the request as resumed, then
   the network or the mock's answer, then the response hold.**
   `RequestStarted` and `ResponseReceived` are sent once, with what the
   app really sent and read; the hit carries what was held. An abort
   sends the held `RequestStarted` and a `RequestFailed`.
2. **Capture first, hold after.** The stored exchange would show a
   request that never went out.

### D. An edit the client refuses

1. **The call fails with an `IOException`** "Breakpoint edit refused: …"
   (a URL that is not absolute, a method OkHttp or Ktor refuses for the
   body), after `RequestStarted` and `RequestFailed`. The developer made
   the edit and sees why.
2. **The edit is dropped and the call goes on unchanged.** It would send
   what the developer chose not to send.

### E. How Ktor holds the response

1. **In the `Monitoring` interceptor after `proceed()`, the edited
   response a new call (`builtCall`, the mocks' internal API) set as the
   subject.** The body is the replacement, the saved body, or the raw
   channel of a streamed one. Headers a resume sets on a request are
   split as Ktor sends them: `Content-Type` into the body, the rest into
   the builder.
2. **A receive pipeline interceptor wrapping `HttpResponse`.** It needs
   the same internal API (`rawContent`) plus a second capture point.

## Decision

A.1, B.1, C.1, D.1, E.1. WebSocket upgrades are never held. Ktor's
internal API use widens DEBT-0083.

## Consequences

- An SDK from this version on holds calls; a desktop of M9.8 shows them
  (SDD 12).
- A held OkHttp call blocks its thread, as the app's own slow server
  would; the app's call timeout, if it set one, can fire first
  (ADR-0111's consequences).
- A held Ktor response that is replaced is not saved again: read once.
