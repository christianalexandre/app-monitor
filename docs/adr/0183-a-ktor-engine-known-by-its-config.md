---
id: ADR-0183
title: A Ktor engine is known by its config, so a wrapper of OkHttp's is marked too
status: Accepted
date: 2026-11-03
superseded_by:
---

# ADR-0183 — A Ktor engine is known by its config, so a wrapper of OkHttp's is marked too

## Context

PLAN-0016 H6.3, DEBT-0100. Beside `appmonitor-okhttp`, `sdk/ktor` marks a request only when its engine
reaches `AppMonitorInterceptor`, which skips and strips the mark (ADR-0129). It asked `engine is
OkHttpEngine`, so an app's engine that delegates to Ktor's was unmarked and each call reached the desktop
twice. ADR-0129's rule, that the mark should not leave the device on an engine that never reaches OkHttp,
is not reopened.

Evidence, Ktor 3.6.0 sources (`ktor-client-okhttp-jvm-3.6.0-sources.jar`, Gradle cache, read on
2026-11-03): `HttpClientEngine.config` is public, and `OkHttpEngine.kt:30` is `class
OkHttpEngine(override val config: OkHttpConfig)`. A wrapper written `HttpClientEngine by delegate` answers
with that config (`JvmMarkingTest`).

## Options

1. **`engine.config is OkHttpConfig`** — public API, one line; covers `OkHttpEngine` and every wrapper that
   hands over its config, the shape Kotlin's `by` generates. A wrapper with a config of its own stays
   unknown.
2. **Walk the engine's fields by reflection looking for an `OkHttpEngine`** — also sees a wrapper with its
   own config, unless the delegate sits behind a lambda; depends on the app's private fields, for a case no
   known app has.
3. **Mark on every engine that is not a known non-OkHttp one** — catches everything, but an unknown engine
   that never reaches OkHttp sends the mark to its server, against ADR-0129.

## Decision

Option 1: `passesAnotherCapture` asks `engine.config is OkHttpConfig`.

## Consequences

- A call on a delegating engine is one exchange and its server never sees the mark
  (`OkHttpEngineDedupeTest`).
- A wrapper with its own config type is still captured twice, the failure DEBT-0100 described, visible on
  the desktop; `aWrapperWithItsOwnConfigIsNotKnown` pins it.
- `consumer-rules.pro` now names `OkHttpConfig` for R8. No test shrinks an app with `sdk/ktor` in it, as
  before this change: a release build holds the no-op.
