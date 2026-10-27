---
id: ADR-0117
title: How the iOS SDK keeps, matches and serves mock rules
status: Accepted
date: 2026-10-25
superseded_by:
---

# ADR-0117 — How the iOS SDK keeps, matches and serves mock rules

## Context

ADR-0110 decided that `URLSession` data and upload tasks are mocked by a
`URLProtocol` put first in `protocolClasses`, background sessions left
out (D.1), and that a mocked exchange carries `RequestStarted.mock_rule_id`
(E.1). ADR-0115 settled the same questions for the Kotlin SDKs: rules in
the session's client, one matcher read from the URL string. Left open
for `sdk/ios`, inside the agreed architecture (`AGENTS.md`): how the
protocol reaches every session, where the rule is chosen, and who sends a
mocked task's events. No public API changes: everything below is
`internal` and debug only (ADR-0048).

Evidence, 2026-10-25, macOS 26.4 host, a scratch binary
(`build/exp/exp.swift`, not committed) and then `MockAnswerTests`:

- `URLProtocol.registerClass(_:)` did not reach a
  `URLSession(configuration: .default)` task: it went to the network.
- Replacing `-[NSURLSessionConfiguration protocolClasses]` with a getter
  that prepends the class reached a session made after the hook, one made
  **before** it, and `URLSession.shared`. `canInit(with: URLSessionTask)`
  was called after `resume()`, with the task running.
- For a task the protocol answered, CFNetwork's
  `__NSCFURLLocalSessionConnection` still ran `_didReceiveData:` and
  `_didFinishWithError:`, the hooks of ADR-0043. ADR-0110's context
  expected the opposite ("they see no connection").
- An `NSError` the protocol fails with reaches both the app and the
  capture's `RequestFailed` with its `NSLocalizedDescriptionKey` intact.

## Options

### A. How the protocol reaches sessions

1. **Replace the `protocolClasses` getter** of `NSURLSessionConfiguration`
   once, at install: it puts `MockProtocol` first, at most once, in every
   configuration without an `identifier`. It reaches sessions made before
   `start`, as the capture does (SDD 07).
2. **Hook session creation** and change the configuration there. Misses
   sessions made before `start` and `URLSession.shared`, which is made
   differently.
3. **`URLProtocol.registerClass`.** Did not reach a custom session.

### B. Where the rule is chosen

1. **At the task's first resume**, in the capture hook that already sends
   `RequestStarted`: the rule is kept on the task's `TaskCapture`, so the
   event carries its id and `canInit(with: task)` only reads it. A set
   that changes between resume and loading cannot make the two disagree.
   Only a data or upload task of a local session, while the monitor runs,
   with the getter installed.
2. **In `canInit`.** It runs after `RequestStarted` was sent, so the event
   could not say it was mocked.

### C. Who sends a mocked task's events

1. **The capture, unchanged**: the connection hooks see the protocol's
   response, body and error (evidence above).
2. **The protocol itself**, as ADR-0110 assumed. Every event would then
   be sent twice.

### D. What a failure is, and the delay

1. **`NSURLErrorDomain` `NSURLErrorNetworkConnectionLost`** with the
   rule's message as its description, after the delay; the delay is a
   `Timer` on the loading thread's run loop, invalidated by
   `stopLoading`, so a cancelled task fails as `NSURLErrorCancelled`.
2. **A code of its own.** The app would see an error its retry logic
   never meets in production (ADR-0115 D.2).

## Decision

A.1, B.1, C.1, D.1. Rules live in the session's `MonitorClient` and are
emptied when the connection ends, as ADR-0115 A.1; the client lists
`interaction` in `Hello`. The matcher runs
`protocol/fixtures/mock_matching.json`. Missing getter: one log line,
nothing mocked.

## Consequences

- An iOS app's tasks are mocked as Android's calls are; the iOS end to
  end asserts one (`make e2e-ios`).
- Any code that reads a configuration's `protocolClasses` in a debug
  build sees `MockProtocol` first; it answers no task without a rule.
- The getter is one more private-runtime hook beside ADR-0043's; a
  missing one turns only mocks off.
