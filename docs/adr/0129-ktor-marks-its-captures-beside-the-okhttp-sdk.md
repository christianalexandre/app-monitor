---
id: ADR-0129
title: The Ktor plugin marks its captures beside the OkHttp SDK, and the interceptor skips and strips the mark
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0129 — The Ktor plugin marks its captures beside the OkHttp SDK, and the interceptor skips and strips the mark

## Context

PLAN-0011 H1.2, DEBT-0087. The Gradle plugin adds `AppMonitorInterceptor`
to every `OkHttpClient` of a debug app (ADR-0112, ADR-0127), Ktor's OkHttp
engine's included. An app that also installs `AppMonitorKtor` sends each
Ktor call twice. On iOS the same problem is solved by the header
`X-AppMonitor-Capture: ktor` (ADR-0064, ADR-0071); on the JVM and Android
`sdk/ktor` never set it (`MARKS_CAPTURED_REQUESTS = false`).

Evidence, Ktor 3.6.0 sources (`ktor-client-okhttp-jvm-3.6.0-sources.jar`,
Gradle cache, read on 2026-10-25): `convertToOkHttpRequest`
(`OkHttpEngine.kt:206`) builds the `okhttp3.Request` from the URL, the
headers, the method and the body only; request attributes are not copied
and no OkHttp tag is set, so a plugin
cannot hand the interceptor an in-process mark, only a header. This is
ADR-0071's finding for Darwin, on OkHttp.

Unlike `URLSession`, OkHttp lets the interceptor change the request before
it is sent, so the mark can be taken off on the device.

## Options

1. **Mark on the JVM and Android always** — simplest; a Ktor app on CIO,
   or without `appmonitor-okhttp`, sends the header to every server, with
   nothing on the device to need it.
2. **Mark only when `appmonitor-okhttp` is on the classpath** — one
   `Class.forName` at first use; the header leaves the device only when
   that SDK is present but its interceptor is not on Ktor's client (Ktor
   on CIO, or no plugin and no hand-written interceptor).
3. **Mark only when the engine is OkHttp and the interceptor is on it** —
   the header never leaves the device; needs `ktor-client-okhttp` as a
   dependency of `sdk/ktor` to read the engine's config, which changes
   which engine `HttpClient()` picks, ADR-0071's objection to option 1.

## Decision

Option 2. `sdk/ktor` on the JVM and Android marks each hop it captures, as
on iOS (ADR-0071), when `appmonitor.sdk.okhttp.AppMonitorInterceptor`
loads. `AppMonitorInterceptor`, application or network, sends a request
carrying `X-AppMonitor-Capture` (any value) on with the header removed and
captures nothing of it, before checking whether the monitor runs.

The header leaving the device in the cases option 2 names follows the
standing owner decision **Debug only** (ADR-0072, ADR-0073, ADR-0093):
`sdk/ktor` is in debug builds only, as ADR-0064 accepted for iOS
(ADR-0094).

## Consequences

- One exchange per Ktor call on OkHttp with both SDKs, whether the
  interceptor came from the plugin or by hand; the server never sees the
  mark there.
- A request the app itself sends through OkHttp with the header is not
  captured and loses it, as the Swift SDK skips it (ADR-0071).
- The JVM tests of `sdk/ktor` have `appmonitor-okhttp` on the classpath
  and the Android host tests do not, so each branch has a test
  (`JvmMarkingTest`, `AndroidMarkingTest`).
- No test runs both SDKs with capture on: outside `sdk/core` nothing
  starts the monitor on the JVM. `OkHttpEngineDedupeTest` runs both with
  the Ktor side on, `AppMonitorInterceptorTest` the interceptor's skip
  with capture on.
