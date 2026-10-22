---
id: ADR-0017
title: Android SDK public API and minSdk
status: Accepted
date: 2026-10-22
superseded_by:
---

# ADR-0017 — Android SDK public API and minSdk

## Context

PLAN-0002 (M1) ships the first SDK an app developer touches: `sdk/core`
(KMP, transport and discovery), `sdk/android-okhttp` (OkHttp capture) and
`sdk/no-op` (release builds). Its public API is a "stop and ask" area
(`AGENTS.md`): once an app compiles against it, every change costs the
developer an edit. What the SDK captures and masks is ADR-0018.

Already decided, not reopened here:

- One entry point per SDK, `AppMonitor.start()` with an options object;
  everything else `internal`; `explicitApi()` in every published module
  (guidelines 01).
- Every SDK ships a no-op artifact with the same public API (guidelines 01).
- Discovery order: manual host → mDNS → `10.0.2.2` on the emulator; the
  desktop listens on `47474`, path `/v1/ws` (ADR-0002, ADR-0009).
- Shared KMP code builds with `minSdk` 21; the SDK's own `minSdk` is open
  (ADR-0007).

Evidence, checked on 2026-10-22:

- OkHttp: latest release 5.5.1 (Maven Central `maven-metadata.xml`). "OkHttp
  works on Android 5.0+ (API level 21+) and Java 8+" (OkHttp `README.md`).
- OkHttp's own capture tool, `HttpLoggingInterceptor`, is an `Interceptor`
  configured by mutators (`redactHeader(name)`, `redactQueryParams(...)`).
  WebSocket frames never pass through interceptors: `OkHttpClient` is a
  `WebSocket.Factory` and frames reach the app's `WebSocketListener` only
  (OkHttp `OkHttpClient.kt`, `newWebSocket`). Capturing frames therefore
  needs a hook on the listener or on the factory.
- Ktor client engines on Android (Ktor docs, `client-engines.md`): `CIO`
  needs Android 7.0+ (API 24), or Java 8 API desugaring below that; `OkHttp`
  engine Android 5.0+; `Android` engine any version. `sdk/core`'s transport
  is a Ktor client WebSocket (PLAN-0002 M1.5).
- Apps targeting Android 17 (SDK 37) need the runtime permission
  `ACCESS_LOCAL_NETWORK` for local network access, mDNS through `NsdManager`
  included; targeting 36 or lower, `INTERNET` is enough
  (developer.android.com/privacy-and-security/local-network-permission).
  AGP 9.3 supports API levels up to 37 (ADR-0007).
- From Android 9 (API 28) cleartext traffic is disabled by default; a
  network security config can allow it, also only for debuggable builds
  through `debug-overrides`
  (developer.android.com/privacy-and-security/security-config). The desktop
  speaks plain `ws://` (ADR-0009).

## Options

### A. Entry point and lifecycle

1. **Explicit `AppMonitor.start(context, options)` / `stop()`**, called by
   the app in `Application.onCreate`. Obvious, testable, works in the no-op
   artifact as an empty function. One line the developer must add.
2. **Auto-start through an AndroidX Startup `Initializer`** (what OkHttp
   itself does), options in manifest meta-data. Zero code, but options in XML
   are untyped, and starting a network client the app never asked for is
   surprising; the no-op artifact must still ship a manifest.

Recommended: 1.

### B. Options

```kotlin
public class AppMonitorOptions(
    public val enabled: Boolean = true,          // false: start() is a no-op
    public val host: String? = null,             // manual host, skips discovery
    public val port: Int = 47474,                // only with host
    public val appName: String? = null,          // shown in the desktop
)
```

1. **Immutable class with defaults** (above) — Kotlin-friendly, a new option
   is a new defaulted parameter (source-compatible, binary-incompatible
   unless `@JvmOverloads` or a builder).
2. **Builder** (`AppMonitorOptions.Builder().host(...).build()`) — binary
   compatible forever, Java-friendly, more code.
3. **DSL** (`AppMonitor.start(context) { host = "..." }`) — terse, but a
   mutable config object leaks into the public API.

Masking and body-limit options are added by ADR-0018 in the same object.
Recommended: 1 with a `@JvmOverloads` constructor; binary compatibility is
not promised before 1.0.

### C. OkHttp capture

1. **`AppMonitorInterceptor()`, added by the developer** with
   `addInterceptor(...)` (application interceptor: one event set per call,
   sees what the app sent and got, after redirects/retries collapse) or
   `addNetworkInterceptor(...)` (one per network hop, sees the real headers
   OkHttp adds, e.g. `Accept-Encoding`, but not cache hits). Developer picks
   per client; documented default: application.
2. **`AppMonitor.okHttp(builder)` extension that installs the interceptor
   and the WebSocket hook** — one call, but hides which kind of interceptor
   is used and mutates the builder.
3. **Global capture (bytecode transform of every `OkHttpClient`)** — zero
   code, needs a Gradle plugin; much larger surface. Out of scope for M1.

WebSocket frames, whichever is chosen:

- **(a) `AppMonitorInterceptor.webSocketListener(listener)`** wraps the
  app's `WebSocketListener` to see received frames and wraps the returned
  `WebSocket` to see sent ones; the developer changes one `newWebSocket`
  call.
- **(b) `AppMonitorWebSocketFactory(client)`**, a `WebSocket.Factory` the app
  uses instead of the client; same coverage, fits apps that inject the
  factory.

Recommended: 1 with (b), the interceptor is the type the developer already
knows from `HttpLoggingInterceptor`.

### D. The SDK's `minSdk`

1. **21**, same as OkHttp. Ktor CIO below API 24 needs the app to enable
   core library desugaring — a build change on the developer's side, or a
   different Ktor engine in `sdk/core`.
2. **24** (Android 7.0), the oldest version Ktor CIO supports without
   desugaring. Apps with `minSdk` 21–23 cannot add the debug dependency
   unless they override the manifest merger in debug.

Recommended: 24. It is a debug-only tool; developers debug on recent
devices and emulators.

### E. Android 17 local network permission

1. **The SDK declares `ACCESS_LOCAL_NETWORK` in its manifest and asks at
   runtime on `start()`** when the app targets 37+ — works out of the box,
   but a library showing a permission dialog the app did not plan for.
2. **The SDK declares it; the app asks** — the SDK logs one clear line and
   falls back to the manual host / emulator path when it is not granted.
3. **Neither; documented** — the developer adds it to the debug manifest.

Recommended: 2. The permission only enters the debug manifest (the no-op
artifact declares nothing).

### F. Cleartext `ws://`

1. **The debug artifact ships a network security config** — it would
   override the app's own config through manifest merging; rejected.
2. **The SDK documents a `debug-overrides` / debug-source-set snippet**; the
   transport fails with a message naming the fix when the policy blocks it.

Recommended: 2 (only one real option).

## Decision

The owner accepted the recommendations:

- **A.** Explicit `AppMonitor.start(context, options)` / `stop()`, called in
  `Application.onCreate`.
- **B.** Immutable `AppMonitorOptions` with defaults and a `@JvmOverloads`
  constructor; no binary-compatibility promise before 1.0.
- **C.** `AppMonitorInterceptor()`, added by the developer (application
  interceptor documented as the default); WebSocket frames through
  `AppMonitorWebSocketFactory(client)` (b).
- **D.** The SDK modules' `minSdk` is **24**. Older versions are not a
  target: supporting them would constrain the SDK for a debug-only tool.
- **E.** The SDK declares `ACCESS_LOCAL_NETWORK`; the app requests it. Not
  granted → one log line and fallback to manual host / emulator.
- **F.** No network security config shipped; a documented `debug-overrides`
  snippet, and a transport error that names the fix.

## Consequences

- Whatever is chosen is mirrored, member by member, by `sdk/no-op`
  (PLAN-0002 M1.8 checks it).
- `minSdk` 24 (if chosen) raises only the SDK modules; `protocol` keeps 21
  (ADR-0007).
- The iOS SDK (M2) follows the same shape — `start(options)`, `stop()`,
  options with defaults — in Swift idiom.
