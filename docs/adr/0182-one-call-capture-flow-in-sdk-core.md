---
id: ADR-0182
title: One capture flow in sdk/core, inline so a call may block or suspend
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0182 — One capture flow in sdk/core, inline so a call may block or suspend

## Context

After ADR-0181, `sdk/android-okhttp` and `sdk/ktor` still each decided what a mock, a
held call, an abort and an edit do to a call (DEBT-0101). The two already differed:
a response breakpoint on a mocked `101`, a failed hold at the response stage closing
the response in OkHttp only, an `IllegalArgumentException` from building edited headers
escaping OkHttp as is while Ktor refused it.

What the two cannot share is how they wait: OkHttp blocks the call's thread, polling
`Call.isCanceled()` (ADR-0111 B.1), and Ktor suspends in a pipeline phase. Nor how they
read and rebuild a call: `RequestBody` and `ResponseBody` against `OutgoingContent` and
a saved `HttpResponse`.

All of it stays behind `InternalAppMonitorApi`: `checkKotlinAbi` and `:sdk:no-op:check`
pass with no dump changed.

## Options

1. **An adapter interface over both calls, with one flow driving it**: the flow owns the
   order too, but needs a type for a call, a body and a response, `suspend` everywhere,
   and `runBlocking` around all of OkHttp's `chain.proceed`, which changes what an
   interrupt does there.
2. **A shared check list and a test comparing the two suites' case names**: the decisions
   stay written twice; a name proves nothing about what a case does.
3. **Decisions in one class, inline at the two points that wait**: `CallCapture` holds a
   call's id and events. `holdRequest`, `holdResponse` and `answer` are `inline`, so the
   `hold` or `wait` lambda may block or suspend, and each library passes only that, how to
   close its response, and how to apply an edit.

## Decision

Option 3. `CallCapture` in `sdk/core` decides: what a hit carries, an abort, a failed
hold, an edit refused (`EDIT_REFUSED`, any exception but a cancellation), what is captured
before a failure, which resume changes a request or a response, that a `101` is never held,
a mock's failure and delay. Each library keeps how it reads and rebuilds its call, and the
order of its own pipeline.

## Consequences

- A rule for a held or mocked call is written once and tested once, `CallCaptureTest`
  on the three targets; `BreakpointTest` and `MockAnswerTest` in each library keep what
  it reads and rebuilds.
- Both now act alike on the differences above: a mocked `101` is not held, a cancelled
  Ktor hold at the response stage cancels its response, and OkHttp refuses a header name
  an edit gets wrong.
- The code a public `inline` function calls has to be public: `started`, `failed` and
  `EDIT_REFUSED` are, behind the opt-in.
- The Swift SDK's flow is its own (`URLSession`), as its capture is (ADR-0037); nothing
  here is shared with it.
- `OutgoingContent.ContentWrapper` carries an edit to a body Ktor streams or that has its
  own headers (`EditedContent`), so `Replay.kt` looks through it for a one-shot body.
