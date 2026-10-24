---
id: ADR-0064
title: How the Ktor plugin and the Swift SDK avoid capturing one exchange twice
status: Proposed
date: 2026-10-24
superseded_by:
---

# ADR-0064 — How the Ktor plugin and the Swift SDK avoid capturing one exchange twice

## Context

PLAN-0005 M4.1 (c). A KMP iOS app can run both SDKs: `sdk/ktor` captures
its Ktor calls, and the Swift SDK captures every `URLSession` task of the
process (ADR-0035 C). With Ktor's Darwin engine the same exchange is then
captured twice. ADR-0003 settled the principle — "a correlation header that
the Swift SDK ignores" — and left its name, its value and whether it leaves
the device open. Whatever reaches the app's backend is a privacy question
(`AGENTS.md`); the Swift SDK's behaviour is part of its contract.

Already decided, not reopened here:

- The Swift SDK hooks the first `resume` of each task and reads
  `task.currentRequest ?? task.originalRequest`, headers included; it
  "never changes" the app's request (ADR-0043,
  `sdk/ios/Sources/AppMonitor/URLSessionCapture.swift:21,156,268`).
- The Ktor plugin sends layer `ADAPTER`, the Swift capture `PLATFORM`
  (ADR-0038).
- The Swift SDK stores its device id in Application Support, file
  `appmonitor-device-id` (`sdk/ios/Sources/AppMonitor/Device.swift:30`);
  the desktop approves per (`device_id`, `app_id`) (ADR-0018 E).

Evidence, Ktor 3.6.0 sources, read on 2026-10-24:

- The Darwin engine "uses `NSURLSession` internally" (`Darwin.kt:17`); its
  delegate is `KtorNSURLSessionDelegate`, and an app may pass its own
  session (`usePreconfiguredSession`, `DarwinClientEngineConfig.kt:115`).
  The CIO engine on iOS opens its own sockets: no `URLSession`, no double
  capture.
- The request the engine builds carries every header a plugin set, so a
  header added by `sdk/ktor` is in `task.currentRequest` when the Swift hook
  runs. Neither SDK can remove it before it is sent without changing the
  app's request.

## Options

### A. How the Swift SDK knows an exchange is already captured

1. **Correlation header** `X-AppMonitor-Capture: ktor`, added by
   `sdk/ktor` on iOS only (no second automatic capture exists on Android).
   The Swift hook skips a task whose request carries it and captures it
   otherwise. A constant value: no id, nothing about the device or the app.
   The header **leaves the device** to the app's backend; a server that
   rejects unknown headers, or a signature computed over all headers before
   the plugin ran, would see a difference in debug builds only.
2. **No header: the Swift SDK skips tasks whose session delegate is Ktor's**
   (`KtorNSURLSessionDelegate`'s Objective-C class name) — nothing leaves the
   device, but it rests on a Kotlin/Native name Ktor does not promise, and
   misses an app's preconfigured session with its own delegate.
3. **Capture both; the desktop shows two rows, labelled `ADAPTER` and
   `PLATFORM`** — no change to either SDK, no header; the developer sees
   every exchange twice, against PLAN-0005's goal and ADR-0003.
4. **`sdk/ktor` captures nothing when the Swift SDK runs** — needs a way for
   Kotlin to see the Swift SDK, and loses the Ktor view (decoded body as
   Ktor saw it, the plugin's layer).

Recommended: 1.

### B. Two monitors in one app

With both SDKs running, the app opens two connections to the desktop.

1. **`sdk/core` on iOS reuses the Swift SDK's device id file**
   (`Application Support/appmonitor-device-id`, same format), so the
   desktop sees one device and asks one approval per app; M4.4 checks the
   desktop accepts two sessions with the same (`device_id`, `app_id`).
2. **Separate ids** — the desktop shows the same phone twice and asks two
   approvals.

Recommended: 1.

## Decision

Empty while `Proposed`.

## Consequences

- A.1: M4.7 adds the header in `sdk/ktor`'s iOS source set and one check
  in the Swift resume hook; the header name becomes part of both SDKs'
  contract, documented in SDD and in the Swift SDK's README.
- A.1: a backend log shows `X-AppMonitor-Capture` on debug builds that run
  the Ktor plugin on iOS.
- B.1 ties `sdk/core`'s iOS storage to the Swift SDK's file name; renaming
  it is a change to both.
