---
id: DEBT-0035
title: A Location that is not an http or https URL is sent as received
status: Paid
date: 2026-10-23
trigger: The iOS SDK (M2) implements the URL masking rule, or a developer reports a secret seen in a custom-scheme redirect
paid_date: 2026-10-23
---

# DEBT-0035 — A Location that is not an http or https URL is sent as received

## What is missing

ADR-0033 masks `Location` and `Content-Location` by resolving them against
the request URL with OkHttp's `HttpUrl.resolve`, which only yields `http` and
`https` URLs. Any other value is sent as received, as the ADR says. A mobile
OAuth redirect to an app scheme
(`Location: com.example.app:/oauth?code=abc`, RFC 8252 §7.1) therefore
reaches the desktop with its `code` in clear, even when `code` is in
`maskedQueryParameters`. The test
`aLocationThatIsNotAnHttpUrlIsSentAsReceived` pins this behaviour.

## Why it was accepted

ADR-0033 decided it, and OkHttp follows only `http` and `https` redirects, so
the app never follows such a `Location` through the interceptor. Masking it
needs a URI parser other than `HttpUrl` and a rule for schemes without an
authority, a privacy decision of its own; an app can already add `Location`
to `maskedHeaders`.

## Trigger

The iOS SDK (M2) implements the URL masking rule, which has to decide what
`URLComponents` does with any scheme, or a developer reports a secret seen in
a custom-scheme redirect.

## Trigger fired

2026-10-23, PLAN-0003 M2.7: the iOS SDK implements the URL masking rule.
`URLComponents` parses `com.example.app:/oauth?code=abc` (scheme, path,
query), so iOS could mask it; OkHttp's `HttpUrl` cannot. iOS ships the
Android behaviour, as ADR-0036 A.1 requires and `protocol/fixtures/masking.json`
pins. Masking it changes what both SDKs mask, a "stop and ask" area:
PLAN-0003 M2.7a asks the owner through a `Proposed` ADR.

## How it was paid

2026-10-23, PLAN-0003 M2.7a: the owner accepted ADR-0044 option 1. Both
SDKs mask every absolute URI in `Location` and `Content-Location` by RFC
3986 generic syntax: iOS drops its `http`/`https` guard and keeps the value
as written; OkHttp's `Masking.uri` splits what `HttpUrl.resolve` rejects.
Three cases in `protocol/fixtures/masking.json` pin it (scheme with a path,
with an authority, opaque); the OkHttp test is now
`aCustomSchemeLocationIsMasked`.
