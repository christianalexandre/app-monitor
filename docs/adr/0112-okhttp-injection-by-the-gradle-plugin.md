---
id: ADR-0112
title: The Gradle plugin adds the OkHttp interceptor in debug builds
status: Proposed
date: 2026-10-25
superseded_by:
---

# ADR-0112 — The Gradle plugin adds the OkHttp interceptor in debug builds

## Context

To capture OkHttp today, an Android app adds
`.addInterceptor(AppMonitorInterceptor())` to each `OkHttpClient.Builder`
it owns (README, SDD 05). A client built inside a library, such as an
image loader or an analytics SDK, is out of reach. The roadmap puts a
Gradle plugin that injects the interceptor in M9 (`docs/plans/README.md`).
What an app developer writes, and what is captured, are "stop and ask"
areas (`AGENTS.md`).

Already decided, not reopened here:

- One plugin, `io.github.christianalexandre.appmonitor`, applied in each
  module that touches the SDK. Build types with `isDebuggable = false`
  count as release. It is published to Maven Central in M10, the same tag
  as the SDK, and "M9's OkHttp injection later lives in the same plugin";
  the samples apply it through `includeBuild` (ADR-0093 A.1, B.1, C.1,
  E.1).
- Native Android apps declare `debugImplementation` for the SDK and
  `releaseImplementation` for its no-op (ADR-0017, ADR-0030).

Evidence, 2026-10-25:

- AGP 9.3.3 (the version in `gradle/libs.versions.toml`):
  `Instrumentation.transformClassesWith(factory, scope, params)` takes an
  `AsmClassVisitorFactory`. `InstrumentationScope` is `PROJECT` (the
  module's own classes) or `ALL` (its dependencies included)
  (`javap` on `gradle-api-9.3.3.jar`).
- OkHttp 5.3.2: every client is made by `OkHttpClient.Builder.build()`.
  `Builder()` starts empty. `Builder(OkHttpClient)`, which `newBuilder()`
  calls, copies the client's interceptors (`javap` on
  `okhttp-jvm-5.3.2.jar`). An interceptor added in `build()` would be
  added a second time to every client made with `newBuilder()`, unless
  `build()` checks first.

## Options

### A. What the plugin rewrites

1. **OkHttp's own `Builder.build()`, scope `ALL`.** On entry it calls
   `AppMonitorOkHttp.install(this)`, which adds the interceptor unless one
   is already in `interceptors()`. Every client of the app is captured,
   including those inside libraries. A hand-written line, or a client made
   with `newBuilder()`, gets no duplicate.
2. **Call sites of `build()` in the app's own classes, scope `PROJECT`.**
   Library clients stay out. It costs a rewrite of every module's classes,
   and there is no single place to test it.
3. **No injection.** The line stays in the README.

Recommended: 1.

### B. Which builds, and the dependency

1. **Debuggable variants only (ADR-0093 C.1), and only when the variant's
   runtime classpath has `appmonitor-okhttp`.** Without the dependency
   nothing is rewritten, and the build logs one line, so a missing
   dependency never becomes a class that fails to load. Release variants
   keep OkHttp's bytes untouched.
2. **The plugin also adds the dependency.** That would hide a version
   choice from the developer.

Recommended: 1.

### C. WebSockets

1. **HTTP only.** `AppMonitorWebSocketFactory` stays an optional line
   (SDD 05). Rewriting `newWebSocket` is a second transform, and it waits
   for a case.
2. **Also rewrite `OkHttpClient.newWebSocket`** to go through the factory.

Recommended: 1.

### D. Opting out

1. **`appMonitor { injectOkHttp = false }`**, a property of the plugin's
   extension. It is on by default.
2. **Opt in** (`injectOkHttp = true`). The default would then still be a
   hand-written step.

Recommended: 1. It removes a step (`AGENTS.md`, short setup).

### E. Where the plugin lives in M9

1. **An included build, `gradle-plugin/`, with injection only.** It has its
   ABI dump (ADR-0093) and Gradle TestKit tests on a consumer project. The
   Android sample applies it through `includeBuild`. The release swap and
   the publication stay in M10 (ADR-0093 E.1).
2. **The release swap in M9 too.** That means one more task, on a plan of
   six.

Recommended: 1.

## Decision

Empty while `Proposed`.

## Consequences

- If accepted: setup for a native Android app shrinks by the interceptor
  line, once the plugin is published (M10). Until then the README keeps
  the line, and SDD 05 says what the plugin does.
- `sdk/android-okhttp` gains one public entry point,
  `AppMonitorOkHttp.install(builder)`, and its no-op twin (ADR-0072).
- A build of an app that uses OkHttp rewrites one class of OkHttp in each
  debuggable variant. Its cost is measured in M9.6.
