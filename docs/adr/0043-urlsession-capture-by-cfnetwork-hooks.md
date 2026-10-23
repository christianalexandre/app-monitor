---
id: ADR-0043
title: URLSession capture hooks task resume and CFNetwork's connection callbacks
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0043 — URLSession capture hooks task resume and CFNetwork's connection callbacks

## Context

PLAN-0003 M2.7 captures every `URLSession` task of the app, sessions created
before `start()` included, uploads of each kind, "never altering the app's
call beyond reading the bounded body". ADR-0035 C.1 left the mechanism to
this task. Already decided, not reopened here: capture runs only in debug
builds and only after `start()` (ADR-0035 C, D), Android's masking rule and
body limit (ADR-0036 A, C), background sessions are M2.9 and WebSocket tasks
M2.8.

Evidence, checked on 2026-10-23 on macOS 26.4 (CFNetwork 3860.500.112, the
`swift test` host) with throwaway probes of the Objective-C runtime:

- A default session is `__NSURLSessionLocal`, its data task
  `__NSCFLocalDataTask`, its upload task `__NSCFLocalUploadTask`.
- `__NSCFURLLocalSessionConnection` (superclass
  `__NSCFURLSessionConnection`, which has a `_task` ivar) defines
  `_didReceiveResponse:sniff:` (`v28@0:8@16B24`), `_didReceiveData:`
  (`v24@0:8@16`) and `_didFinishWithError:` (`v24@0:8@16`).
- `resume` is defined on `NSURLSessionTask`; `__NSCFLocalSessionTask`
  overrides only `_onqueue_resume`.
- The upload body is not in the task's request (`httpBody` and
  `httpBodyStream` nil); a file is in the private `uploadFile`, data
  nowhere readable. The public constructors call
  `uploadTaskWithRequest:fromData:` / `…:completionHandler:`; the async
  `upload(for:from:)` calls `_uploadTaskWithRequest:fromData:delegate:completionHandler:`
  instead, all defined on `NSURLSession`, none overridden by
  `__NSURLSessionLocal`.
- `_didReceiveData:` calls itself again with the same data object (the
  sniffed first chunk); counting both made a 300 000-byte body 316 341.
- The connection reports `kCFErrorDomainCFNetwork` errors whose codes are the
  `NSURLErrorDomain` codes the app receives (-1001 timeout, -1004 refused).
- Session `httpAdditionalHeaders` are in `currentRequest` at resume; a
  cookie from `HTTPCookieStorage` reaches the server but is not in the
  request at resume.
- The previous tool registers a `URLProtocol` subclass in every
  configuration's `protocolClasses` (ADR-0035 context).

## Options

1. **`URLProtocol` in every configuration** (the previous tool) — public
   API, but the protocol re-runs each request in its own session: the app's
   delegate stops receiving auth challenges, redirects and metrics, its
   certificate pinning no longer applies, and cache and cookie handling
   become the SDK's. That alters the call.
2. **Delegate proxying** — wrap each session's delegate. Public API, but
   completion-handler and `async` tasks never call a data delegate, so their
   bodies are lost, and `URLSession.shared` has no delegate to wrap.
3. **Hook task resume and the connection callbacks** — replace the
   implementations above so each one records, then calls the original with
   the same arguments. Every task of a local session is seen whatever API
   created it, bodies included, and the app's call is untouched. Private
   classes and selectors: an OS update can rename them, and the hooks must
   not reach release builds.

## Decision

Option 3. `resume` emits `RequestStarted` once per task while the monitor
runs; the connection callbacks fill the response and emit `ResponseReceived`
or `RequestFailed` at finish; the upload constructors keep their body on the
task. Each hook calls the original unchanged, `_didReceiveData:` ignores a
call made from inside itself, and an error is sent as `domain code:
description` with CFNetwork's domain mapped to `NSURLErrorDomain`, never its
`userInfo` (it holds the URL unmasked). If any of the `resume` or connection
methods is missing, nothing is hooked and one line is logged.

## Consequences

- Every OS version the SDK supports must keep these names: the M2.12 sample
  and M2.13 end to end run on the iOS simulator and a device; until then the
  hooks are proven on the macOS host only.
- The private names must stay out of release builds: M2.10 puts the
  implementation, these hooks included, under `#if DEBUG` (ADR-0035 D.1)
  and M2.12's symbol check covers them.
- Headers that CFNetwork adds at send time (`Cookie` from the cookie
  storage, `User-Agent`, `Accept-Encoding`) are not captured (DEBT-0037).
- Background sessions run out of process and use other classes; M2.9
  decides their capture.
