---
id: DEBT-0043
title: Non-correctness findings of the second M2 milestone review
status: Open
date: 2026-10-23
trigger: The next PR after #114 that touches Masking.swift, or a run of the iOS SDK tests on an iOS 15 or 16 simulator
paid_date:
---

# DEBT-0043 — Non-correctness findings of the second M2 milestone review

## What is missing

Findings of the `code-review` of `9dee53b..7b9d596` that are not wrong
results, crashes, hangs, leaks, races or privacy issues (ADR-0016):

- `sdk/ios/Sources/AppMonitor/Masking.swift`: `url(_:)` returns the value
  unmasked when `URLComponents(string:)` fails. Every caller passes a value
  `URL` parsed first, and on the macOS 26 host no value probed (bad port,
  spaces, `%zz`, `|`, `{}`, raw `@` in the password, non-ASCII path) made
  `URL` accept and `URLComponents` reject it. iOS 15–16, the deployment
  target, have a separate `URL` parser and were not probed; falling back to
  `uri(_:)` there costs one line.
- `Masking.swift`: `uri(_:)` splits and rebuilds the query and fragment even
  when `maskedQueryParameters` is empty; `url(_:)` and OkHttp's `fields`
  return early.
- `Masking.swift`: a custom-scheme value Foundation parses (`myapp://cb?…`)
  now goes through `uri(_:)` instead of `URLComponents`; only the three
  ADR-0044 vector cases pin that path.

## Why it was accepted

None of them changes what reaches the desktop for an input seen today; the
first is a guard for a parser not available on this machine.

## Trigger

The next PR that touches `Masking.swift` pays the items, or a run of
`swift test`'s masking vector on an iOS 15 or 16 simulator, which settles
the first. #114 (M2.R2) touched `Masking.swift` and left them unpaid; the
third milestone review found it, and the next such PR after #114 pays them
together with DEBT-0044.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
