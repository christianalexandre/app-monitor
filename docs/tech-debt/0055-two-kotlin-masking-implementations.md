---
id: DEBT-0055
title: Two Kotlin implementations of the masking rule
status: Paid
date: 2026-10-24
trigger: A new masking rule, or a vector case that passes in one of `sdk/android-okhttp` and `sdk/ktor` and fails in the other
paid_date: 2026-11-03
---

# DEBT-0055 — Two Kotlin implementations of the masking rule

## What is missing

`sdk/android-okhttp` masks with OkHttp's `HttpUrl`; `sdk/ktor` masks with
string operations and its own RFC 3986 resolver (ADR-0069 C.1). Both pass
`protocol/fixtures/masking.json`, but a new rule has to be written twice,
and outside the vector they may differ (OkHttp normalises a URL it parses,
`sdk/ktor` keeps it as written).

## Why it was accepted

Merging means `sdk/android-okhttp` drops `HttpUrl` and changes what OkHttp
apps see, which is not M4.5's task; the vector keeps the two equal on every
case it lists.

## Trigger

A new masking rule, or a vector case that passes in one of
`sdk/android-okhttp` and `sdk/ktor` and fails in the other.

Re-triggered on 2026-11-03 by PLAN-0015 H5.6: its three new cases pinned
existing behaviour and passed in both unchanged, so nothing was written
twice. A change to the file alone is no cost; a rule written twice is.

## How it was paid

Paid by PLAN-0016 H6.3a (ADR-0181): `Masking` lives in `sdk/core`, written once. What
measuring it taught: `HttpUrl` was also the only thing masking a backslashed `Location`,
so each library still resolves its own; the old class rewrote a masked URL's other
fields, which the shared rule keeps as written. Left: a URL `HttpUrl` normalises before
masking still differs from Ktor's, as each library sends it.
