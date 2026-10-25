---
id: ADR-0115
title: How the Kotlin SDKs keep, match and serve mock rules
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0115 — How the Kotlin SDKs keep, match and serve mock rules

## Context

ADR-0110 decided that the device matches the desktop's rules (A.1), what
a rule matches (B.1) and answers (C.1), that OkHttp and Ktor serve them
(D.1), and that a mocked exchange carries `RequestStarted.mock_rule_id`
(E.1). ADR-0113 decided the whole-set `MockRules` message. Left open,
inside the agreed architecture (`AGENTS.md`): where the rules live in
`sdk/core`, how a capture module asks for one, and where in Ktor the
call is answered.

Evidence, 2026-10-25, Ktor 3.6.0 (`ktor-client-core-jvm-3.6.0-sources.jar`)
and OkHttp 5.3.2:

- `HttpSend` runs `client.sendPipeline.execute(builder, body)` once per
  hop and casts its result to `HttpClientCall` (`plugins/HttpSend.kt`).
  The engine's interceptor in `HttpSendPipeline.Engine` builds the call
  with `HttpClientCall(client, requestData, responseData)`, both
  `@InternalAPI`; `takeFromWithExecutionContext` and the builder's
  `body` setter are `@InternalAPI` too (`engine/HttpClientEngine.kt`,
  `request/HttpRequest.kt`). `PipelineContext.subject` is a public `var`
  and `finish()` ends the pipeline with it (`ktor-utils`,
  `pipeline/PipelineContext.kt`).
- `HttpSendPipeline.Receive`, skipped by `finish()`, runs
  `client.receivePipeline` (`HttpClient.kt`), where `SaveBody` and
  response observers sit. `HttpCallValidator` checks the response in a
  `Send` hook, outside the send pipeline (`plugins/HttpCallValidator.kt`).
- On native targets `HttpStatement.execute { }` runs its block in
  `response.coroutineContext[ContinuationInterceptor]!!`
  (`statement/HttpStatement.kt:73`); a call context without a
  dispatcher threw a `NullPointerException` on the simulator in this
  task's first run.
- An OkHttp application interceptor may return a `Response` without
  calling `proceed` (ADR-0110's evidence); `AppMonitorInterceptor`
  already skips WebSocket upgrades before any capture (SDD 05).

## Options

### A. Where the rules live

1. **In the session's `MonitorClient`, cleared when the session ends.**
   The client lists `interaction` in `Hello` on Android and iOS, keeps
   the last `MockRules` it read after `HelloAck`, and empties it when
   the connection closes or the monitor stops. ADR-0110 A.1: no mock
   outlives its desktop.
2. **In a process-wide store kept across reconnects.** A desktop that
   restarts sends the set again at `HelloAck` anyway; keeping the old one
   meanwhile serves rules nobody can see.

### B. How a capture module asks

1. **`MonitorBridge.mockFor(method, url)`, matched in `sdk/core` from
   the URL string.** One matcher for both modules, run by one vector
   (`protocol/fixtures/mock_matching.json`); host and path come from the
   URL as the module writes it, by RFC 3986 appendix B. Behind
   `@InternalAppMonitorApi`, so no public API changes (ADR-0025).
2. **Each module matches with its own URL type.** Two matchers to keep
   equal, which is what the vector exists to avoid.

### C. Where Ktor answers

1. **In the plugin's `HttpSendPipeline.Monitoring` interceptor**, where
   it already captures (ADR-0069): the call is built as the engine
   builds it, set as the pipeline's subject, and `finish()` skips the
   engine. `RequestStarted` keeps the headers the app would have sent,
   cookies included. The call context is the engine's dispatcher and a
   new `Job`. The receive pipeline is skipped: a mocked response is not
   saved by `SaveBody`, which a single read does not need.
2. **In a `Send` hook (ADR-0110's evidence).** It sits outside the send
   pipeline, before `HttpCookies` adds its header, so the captured
   request would differ from the one a real call sends, and a second
   capture point would duplicate the first one's work.

### D. What a failure throws

1. **An `IOException` with the rule's message** in both modules
   (`java.io` for OkHttp, `kotlinx.io` for Ktor), after the delay. A
   cancelled OkHttp call, or an interrupted delay, fails as OkHttp does.
2. **A dedicated exception type.** The app would see an error its retry
   logic never meets in production.

## Decision

A.1, B.1, C.1, D.1. A rule with neither answer, from a newer desktop, is
ignored. Ktor's `@InternalAPI` use is DEBT-0083.

## Consequences

- An SDK from this version on lists `interaction`; the desktop pushes
  rules to it (SDD 12).
- `RequestStarted.mock_rule_id` is on the wire; the desktop does not
  store or show it yet (PLAN-0010).
- A Ktor upgrade can break the mocked call's construction without a
  compile error in apps: `MockAnswerTest` in `sdk/ktor` runs on every
  target.
