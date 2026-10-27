---
id: ADR-0127
title: The Gradle plugin rewrites OkHttp's client constructor
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0127 — The Gradle plugin rewrites OkHttp's client constructor

## Context

ADR-0112 A.1 has the plugin call `AppMonitorOkHttp.install(this)` at the
start of `OkHttpClient.Builder.build()`, because its context read "every
client is made by `OkHttpClient.Builder.build()`". The M9 milestone
review (PLAN-0010) found that claim wrong.

Evidence, OkHttp 5.3.2 (`okhttp-android-5.3.2-sources.jar`,
`okhttp3/OkHttpClient.kt`, and `javap -c` on `okhttp-jvm-5.3.2.jar`):

- `constructor() : this(Builder())` (line 275) makes a client from a new
  `Builder` without calling `build()`. An app that writes
  `OkHttpClient()`, a common way to get a default client, is not captured.
- `fun build(): OkHttpClient = OkHttpClient(this)` (line 1375). Both paths
  end in the constructor `<init>(Lokhttp3/OkHttpClient$Builder;)V`, and so
  does a subclass's `super(builder)`.
- That constructor's bytecode starts with `aload_1`, then
  `Intrinsics.checkNotNullParameter`, before `Object.<init>`. A static call
  on the argument before `super()` is already in the class and passes the
  verifier.

Not reopened: scope `ALL`, debuggable variants with the SDK only, the
opt-out, and `install`'s check for an existing interceptor (ADR-0112 A.1,
B.1, D.1; ADR-0126).

## Options

1. **Rewrite `OkHttpClient(builder)`.** On entry it calls
   `AppMonitorOkHttp.install(builder)`. One rewritten method, which every
   client goes through.
2. **Rewrite `OkHttpClient()` as well as `Builder.build()`.** Two methods.
   The no-argument constructor creates its `Builder` inline, so the call
   has to go between `new Builder` and `this(builder)`.

## Decision

Option 1: the plugin rewrites the client constructor. This replaces only
the method that ADR-0112 A.1 named. The rest of ADR-0112 stands.

## Consequences

- `OkHttpClient()`, `Builder.build()`, `newBuilder().build()` and
  subclasses are all captured. `ClientVisitorTest` loads OkHttp's real
  class rewritten and counts the entry point's calls for each of them.
- The transform matches `okhttp3.OkHttpClient` and not its `Builder`. A
  future OkHttp that adds a second constructor taking a `Builder`, or
  that changes this one's descriptor, would go unmatched. The functional
  test, which counts the call in a consumer's APK, then fails.
- `AppMonitorOkHttp.install`'s signature is unchanged, so the public API
  stays the same.
