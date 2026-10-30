---
id: DEBT-0101
title: The OkHttp and Ktor captures are written twice
status: Open
date: 2026-10-30
trigger: "A capture bug fixed in one of `sdk/android-okhttp/src/commonMain/kotlin/appmonitor/sdk/okhttp/AppMonitorInterceptor.kt` and `sdk/ktor/src/commonMain/kotlin/appmonitor/sdk/ktor/AppMonitorKtor.kt` (or its `Breakpoints.kt`) that the other has too, or a third Kotlin capture module"
paid_date:
---

# DEBT-0101 — The OkHttp and Ktor captures are written twice

## What is missing

`sdk/android-okhttp` and `sdk/ktor` each define their own `CaptureSink`
and `MonitorSink` (`Capture.kt` in both), their own masking
(DEBT-0055), and their own mock, breakpoint and replay flow around the
same `MonitorBridge` calls: when to look a mock up, when to hold the
request and the response, what an edit may change, what goes in each
`BreakpointHit`. A rule written for one is copied by hand into the
other. Symptom: the two SDKs act differently on the same call. An
external review found one: Ktor answered a WebSocket upgrade with a
mock that OkHttp skips (fixed by #311).

## Why it was accepted

The two hook into different places (an OkHttp `Interceptor` on the
calling thread, a Ktor `HttpSendPipeline` phase in a coroutine), with
different body types (`RequestBody` and `ResponseBody`, `OutgoingContent`
and a saved `HttpResponse`). A shared layer needs an abstraction over
both that does not exist yet. Each SDK has its own `BreakpointTest`,
`MockAnswerTest` and `ReplayTest`, written to the same cases by hand.
Nothing checks that the two lists stay equal: a case one of them lacks,
as the upgrade mock was, fails nowhere. Only masking has a shared vector
(DEBT-0055).

## Trigger

A capture bug fixed in one of
`sdk/android-okhttp/src/commonMain/kotlin/appmonitor/sdk/okhttp/AppMonitorInterceptor.kt`
and `sdk/ktor/src/commonMain/kotlin/appmonitor/sdk/ktor/AppMonitorKtor.kt`
(or `sdk/ktor/src/commonMain/kotlin/appmonitor/sdk/ktor/Breakpoints.kt`)
that turns out to exist in the other too, or a third Kotlin capture
module. Then the decisions (mock lookup, hold, edit, hit contents) move
to `sdk/core` behind `MonitorBridge`, each SDK keeping only how it reads
and rebuilds its own call.

## How it was paid

> Filled when closing.
