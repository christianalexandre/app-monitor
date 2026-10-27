---
id: DEBT-0055
title: Two Kotlin implementations of the masking rule
status: Open
date: 2026-10-24
trigger: The next change to `protocol/fixtures/masking.json`, or a vector case that passes in one of `sdk/android-okhttp` and `sdk/ktor` and fails in the other
paid_date:
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

The next change to `protocol/fixtures/masking.json`, or a vector case that
passes in one of `sdk/android-okhttp` and `sdk/ktor` and fails in the
other.

## How it was paid

> Filled when closing.
