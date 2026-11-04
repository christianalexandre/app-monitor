---
id: ADR-0189
title: iOS headers sent are read from the transaction metrics at the response
status: Accepted
date: 2026-11-04
superseded_by:
---

# ADR-0189 — iOS headers sent are read from the transaction metrics at the response

## Context

DEBT-0037: the iOS SDK records a request's headers from `currentRequest`
when the task first resumes (ADR-0043), before CFNetwork adds `Cookie` from
the session's cookie storage, `User-Agent`, `Accept-Language`,
`Accept-Encoding` and a body's default `Content-Type`. The desktop shows
fewer headers than the server receives. Already decided, not reopened here:
the hooks of ADR-0043, `RequestStarted` sent at resume (a held task's at the
hold's end, ADR-0123), the masking rule (ADR-0036 A, ADR-0034).

Evidence, checked on 2026-11-04 on macOS 26.4 (CFNetwork 3860.500.112) with
throwaway probes against a Network.framework server that dumped the bytes it
received:

- A POST with a body and a stored cookie went out with `Host`, `Accept`,
  `Content-Type`, `Connection`, `Cookie`, `Accept-Language`,
  `Content-Length`, `Accept-Encoding` and `User-Agent`. The task's
  `currentRequest` had none before resume and, after it finished, still no
  `Cookie`, `User-Agent`, `Host` or `Connection`.
- `URLSessionTaskTransactionMetrics.request` held exactly the headers on the
  wire, those nine.
- A `URLSession.shared` task (no delegate, so no
  `urlSession(_:task:didFinishCollecting:)`) answers the private
  `_incompleteCurrentTaskTransactionMetrics` (`@16@0:8`, on `NSURLSessionTask`)
  with that transaction, `Cookie` included; `_incompleteTaskMetrics` gives
  the whole `URLSessionTaskMetrics`. The new test reads it in ADR-0043's
  `_didReceiveResponse:sniff:` hook, before the original runs.
- No event of the protocol carries request headers after `RequestStarted`.

## Options

1. **Read the transaction at the response, send it with
   `ResponseReceived`** — a new `request_headers` field, masked as
   `RequestStarted.headers`, that the desktop puts in place of the started
   headers when not empty. One more private selector, behind `responds(to:)`;
   the in-flight row shows the resume headers until the response.
2. **Hold `RequestStarted` until the response** — no protocol change, but a
   pending call disappears from the live list and the breakpoint's request
   stage (ADR-0123) has nothing to show.
3. **Rebuild the headers at resume** from the cookie storage and the
   configuration — public API, but `User-Agent`, `Accept-Language` and the
   cookie policy are CFNetwork's to decide: what is rebuilt can disagree
   with what was sent, which is the bug.
4. **A second `RequestStarted`** — the desktop's started update rewrites the
   start time, and every reader of the event would learn it can repeat.

## Decision

Option 1. The response hook reads the task's
`_incompleteCurrentTaskTransactionMetrics.request` headers for a task that
went to the network (neither mocked nor held), `ResponseReceived` carries
them, masked, in `request_headers`, and the desktop's store and live list
put them in the exchange's request, never in the response. Capturing a
`Cookie` that was always sent, masked by today's rule, follows the standing
owner decision *Little masking* of `AGENTS.md`: the desktop stores the
traffic as the device sent it, no new masking rule (ADR-0081, ADR-0089).

## Consequences

- The desktop shows what the server received, cookie and agent included,
  from the moment the response arrives; for a redirect, the last hop's.
- A request that fails without a response, a held or mocked task and a
  background session's task keep their resume headers (DEBT-0125).
- Older desktops ignore the field; Kotlin SDKs never set it, their headers
  are already the ones sent.
- One more private name joins ADR-0043's, proven by `swift test` on the
  macOS host and `make swift-sim` on the simulator; missing, the headers
  stay the resume ones.
