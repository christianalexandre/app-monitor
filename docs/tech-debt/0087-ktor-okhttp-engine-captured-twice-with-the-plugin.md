---
id: DEBT-0087
title: The Gradle plugin captures Ktor's OkHttp engine a second time
status: Open
date: 2026-10-25
trigger: The first Android app with both `appmonitor-ktor` and `appmonitor-okhttp` that applies the plugin, or the next PR that changes `gradle-plugin/src/main/kotlin/appmonitor/gradle/OkHttpInjection.kt`
paid_date:
---

# DEBT-0087 — The Gradle plugin captures Ktor's OkHttp engine a second time

## What is missing

The plugin adds `AppMonitorInterceptor` to every `OkHttpClient` of a
debug app (ADR-0112), Ktor's OkHttp engine's included. An app that also
installs `AppMonitorKtor` then sends each Ktor call twice: once from the
Ktor plugin, once from the interceptor. On Android the Ktor plugin sets
no capture header to skip on (ADR-0071, SDD 08). Symptom: two exchanges
for one call, a request id each.

## Why it was accepted

No sample or known app has both SDKs: the Android sample uses OkHttp,
the KMP sample Ktor without `appmonitor-okhttp`, and the plugin rewrites
nothing without it (ADR-0126 A.1). The fix, the interceptor skipping a
request the Ktor plugin marked, is the Android half of ADR-0064's header.

## Trigger

The first Android app with both `appmonitor-ktor` and `appmonitor-okhttp`
that applies the plugin, or the next PR that changes
`gradle-plugin/src/main/kotlin/appmonitor/gradle/OkHttpInjection.kt`.

## How it was paid

> Filled when closing.
