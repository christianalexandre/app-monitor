---
id: DEBT-0099
title: An older appmonitor-okhttp beside a newer Gradle plugin breaks WebSockets in debug
status: Paid
date: 2026-10-29
trigger: The first SDK release after the one that adds `AppMonitorOkHttp.newWebSocket`, or an app reports a `NoSuchMethodError` on it
paid_date: 2026-10-31
---

# DEBT-0099 — An older appmonitor-okhttp beside a newer Gradle plugin breaks WebSockets in debug

## What is missing

The plugin rewrites `OkHttpClient.newWebSocket` to call
`AppMonitorOkHttp.newWebSocket` whenever `appmonitor-okhttp` is in the
graph, whatever its version (ADR-0159). If an app forces an
`appmonitor-okhttp` older than the plugin, through a constraint or a BOM,
every WebSocket in its debug build throws `NoSuchMethodError`.

## Why it was accepted

The plugin adds the SDK at its own version (ADR-0157). Only an explicit
downgrade gets an older one, and no published version lacks the method yet:
it ships with the first publication. Found by the review of #297 to #299.

## Trigger

The first SDK release after the one that adds `AppMonitorOkHttp.newWebSocket`
(an older version then exists to be forced), or an app reports a
`NoSuchMethodError` on it. The fix lives in `OkHttpInjection.kt`: rewrite
only when the resolved `appmonitor-okhttp` is the plugin's version.

## How it was paid

Paid before the first publication. The rewrite runs only when the
resolved `appmonitor-okhttp` is the plugin's version, and
`appMonitorOkHttpCheck<Variant>` fails the debug build with one line
naming both versions and `injectOkHttp = false` otherwise.
`OkHttpInjectionTest` forces a published 0.0.1 and reads that line
(ADR-0169). A newer SDK than the plugin fails too: aligning both is the
answer, as the plugin adds its own version.
