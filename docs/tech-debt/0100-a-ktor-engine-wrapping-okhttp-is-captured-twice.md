---
id: DEBT-0100
title: A Ktor engine that wraps Ktor's OkHttp engine is captured twice
status: Paid
date: 2026-10-29
trigger: An app reports each Ktor call twice beside appmonitor-okhttp, or the next PR that changes how `Platform.android.kt` tells the engine
paid_date: 2026-11-03
---

# DEBT-0100 — A Ktor engine that wraps Ktor's OkHttp engine is captured twice

## What is missing

With `install(AppMonitorKtor)` and `appmonitor-okhttp` both in an Android
or JVM app, the Ktor plugin marks its requests only when the client's
engine is Ktor's `OkHttpEngine`, so `AppMonitorInterceptor` skips them
(ADR-0129). An engine of the app's own that delegates to an `OkHttpEngine`
is another class: its requests go unmarked, and the desktop shows each one
twice, once from Ktor and once from OkHttp.

## Why it was accepted

From the outside, a delegating engine looks like any other engine, and
marking requests of an engine that never reaches OkHttp would send the
mark to the server. The engine is now known by its class, not its name, so
R8 renaming it in a minified debug build no longer causes this. A wrapping
engine is a case no known app has. Found by the review of #297 to #302.

## Trigger

An app reports each Ktor call twice beside `appmonitor-okhttp`, or the next
PR that changes how `Platform.android.kt` (or `Platform.jvm.kt`) tells the
engine.

## How it was paid

PLAN-0016 H6.3c (`fix/ktor-wrapped-engine-one-seam`, ADR-0183): the engine is known by `engine.config is OkHttpConfig`,
so a wrapper written `HttpClientEngine by delegate` is marked and captured once (`OkHttpEngineDedupeTest`). What is
left: a wrapper with a config of its own is still captured twice, pinned by `aWrapperWithItsOwnConfigIsNotKnown`.
