---
id: ADR-0046
title: Background URLSession capture hooks the session's callbacks from nsurlsessiond
status: Accepted
date: 2026-10-23
superseded_by:
---

# ADR-0046 — Background URLSession capture hooks the session's callbacks from nsurlsessiond

## Context

PLAN-0003 M2.9 captures background `URLSession` traffic. ADR-0004 layer 1
wrote "background sessions via delegate proxying"; ADR-0043 then chose
runtime hooks over delegate proxying for local sessions, and left background
sessions to this task because they run out of process. Already decided, not
reopened: capture runs only after `start()` (ADR-0035 C), masking and body
limit as ADR-0036, `RequestStarted` from the `resume` hook of ADR-0043.

Evidence, checked on 2026-10-23 on the iOS 26.5 simulator (Xcode 26.6) with a
throwaway probe hosted in an app (ADR-0047); a bare `xctest` process logs
"remote session is unavailable" and every background task fails:

- A background session is `__NSURLBackgroundSession`, its tasks
  `__NSCFBackgroundDownloadTask`, `…UploadTask`, `…DataTask`, all under
  `__NSCFBackgroundSessionTask`, none overriding `resume`, so ADR-0043's
  `resume` hook already sees them; the session does not override the upload
  constructors either.
- `__NSURLBackgroundSession` receives `nsurlsessiond`'s callbacks by task
  identifier (`Q` in each type encoding):
  `backgroundDataTask:didReceiveData:withReply:` (`v40@0:8Q16@24@?32`),
  `backgroundDownloadTask:didFinishDownloadingToURL:response:reply:`
  (`v48@0:8Q16@24@32@?40`),
  `backgroundTask:didCompleteWithError:taskMetrics:info:reply:`
  (`v56@0:8Q16@24@32@40@?48`).
- The downloaded file is in the app's container when the second one runs,
  and the app's `didFinishDownloadingTo` gets the same file afterwards.
- `NSURLSessionTask` defines `session`, which returns the task's session.
- With a delegate set, `session.delegate` returns the app's object; a proxy
  would be what the app reads back.

## Options

1. **Delegate proxying** (ADR-0004's wording) — wrap the delegate at
   `URLSession(configuration:delegate:delegateQueue:)`. Public protocols,
   but `session.delegate` returns the proxy, every `responds(to:)` the
   session asks must answer as the app's object would, and sessions created
   before `start()` keep their own delegate.
2. **Hook the session's callbacks** — replace the three methods above so
   each records, then calls the original with the same arguments; the task
   is found by identifier in a table kept on the session. The app's delegate
   is never touched. Three more private names, same risk as ADR-0043.

## Decision

Option 2, replacing ADR-0004's "via delegate proxying" for background
sessions. The `resume` hook registers a captured task on its background
session; data chunks and the downloaded file (read up to `maxBodyBytes`
before the app's delegate can move it) fill its capture, and completion sends
`ResponseReceived` or `RequestFailed` with the task's response. If any of the
three methods is missing, background tasks get only `RequestStarted` and one
line is logged.

## Consequences

- The capture is proven by hosted tests on the simulator (`make swift-sim`,
  ADR-0047); `swift test` on macOS cannot run a background session.
- A task resumed before `start()` or in an earlier launch of the app, and
  finished after it, is not captured (DEBT-0039).
- The new names stay out of release builds with the rest of the hooks
  (M2.10, ADR-0035 D.1).
