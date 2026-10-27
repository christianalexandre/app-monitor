---
id: DEBT-0044
title: Non-correctness findings of the third M2 milestone review
status: Open
date: 2026-10-23
trigger: The next PR that changes the masking in `Masking.swift` or `sdk/android-okhttp/src/commonMain/kotlin/appmonitor/sdk/okhttp/Capture.kt` (the `Masking` class), or `protocol/fixtures/masking.json`
paid_date:
---

# DEBT-0044 — Non-correctness findings of the third M2 milestone review

## What is missing

Findings of the `code-review` of `7b9d596..70c7f57` that are not wrong
results, crashes, hangs, leaks, races or privacy issues (ADR-0016):

- `sdk/android-okhttp/.../Capture.kt`: without the scheme guard (M2.R3),
  `uri` rewrites the query of any value `HttpUrl.resolve` rejects, even one
  with no `//` authority (`not a url?code=1`). It masks more, never less,
  but no vector case pins a value with neither scheme nor authority.
- `protocol/fixtures/masking.json`: the last-`@` search (M2.R2) has no case
  for an `@` in the path (`http://h:p/x@y`) or for a value with no path
  (`http://user:p@ss@h:p`), the two bounds of that search.
- `Capture.kt` and `sdk/ios/Sources/AppMonitor/Masking.swift`: the two
  `userInfo` find the scheme differently (Kotlin's `SCHEME` regex, Swift's
  first `:` and a check per character); the vector covers simple schemes
  only, so the two can drift.
- Both SDKs: the `uri` fallback is two hand-written RFC 3986 user info
  parsers, patched one delimiter per review round. One shared rule, written
  once in SDD 05 and pinned by the vector, would end that.

## Why it was accepted

None of them changes what reaches the desktop for an input seen today. The
fallback only runs on values no URL parser on the device accepts.

## Trigger

The next PR that changes the masking in `Masking.swift` or in
`sdk/android-okhttp/src/commonMain/kotlin/appmonitor/sdk/okhttp/Capture.kt`
(the `Masking` class), or `protocol/fixtures/masking.json`, pays the items.
Re-triggered in `feat/kotlin-sdk-mocks` (M9.4), which touched the OkHttp
file for mock rules only: paying masking items there would mix two
reviews in one PR. The OkHttp file is named
by path since sdk/ktor has a Capture.kt of its own (PLAN-0005 M4.5),
whose masking is DEBT-0055.

## How it was paid

> Filled when closing. What the solution taught, and what is left of it.
